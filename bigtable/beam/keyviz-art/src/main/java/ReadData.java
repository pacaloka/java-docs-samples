import com.google.cloud.bigtable.beam.AbstractCloudBigtableTableDoFn;
import com.google.cloud.bigtable.beam.CloudBigtableConfiguration;
import com.google.cloud.bigtable.beam.CloudBigtableTableConfiguration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter.RowRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.joda.time.Duration;

public class ReadData {

  static final long KEY_VIZ_WINDOW_MINUTES = 15;
  static final String COLUMN_FAMILY = "cf1";
  static final long START_TIME = getStartTime();

  public static void main(String[] args) {
    ReadDataOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(ReadDataOptions.class);
    Pipeline p = Pipeline.create(options);
    CloudBigtableTableConfiguration bigtableTableConfig =
        new CloudBigtableTableConfiguration.Builder()
            .withProjectId(options.getBigtableProjectId())
            .withInstanceId(options.getBigtableInstanceId())
            .withTableId(options.getBigtableTableId())
            .build();

    p.apply(GenerateSequence.from(0).withRate(1, new Duration(1000)))
        .apply(
            ParDo.of(
                new DoFn<Long, Integer>() {
                  @ProcessElement
                  public void processElement(OutputReceiver<Integer> out) {
                    long timestampDiff = System.currentTimeMillis() - START_TIME;
                    long minutes = (timestampDiff / 1000) / 60;
                    out.output(Math.toIntExact(minutes / KEY_VIZ_WINDOW_MINUTES));
                  }
                }))
        .apply(ParDo.of(new ReadFromTableFn(bigtableTableConfig, options)));
    p.run().waitUntilFinish();
  }

  private static long getMaxInput(ReadDataOptions options) {
    return options.getGigabytesWritten() * 1000 / options.getMegabytesPerRow();
  }

  static class ReadFromTableFn extends AbstractCloudBigtableTableDoFn<Integer, Void> {

    List<List<Float>> imageData = new ArrayList<>();
    String[] keys;

    public ReadFromTableFn(CloudBigtableConfiguration config, ReadDataOptions readDataOptions) {
      super(config);
      keys = new String[Math.toIntExact(getMaxInput(readDataOptions) + 1)];
      downloadImageData(readDataOptions.getFilePath());
      generateRowkeys(getMaxInput(readDataOptions));

    }

    @ProcessElement
    public void processElement(@Element Integer timeOffsetIndex, PipelineOptions po) {
      ReadDataOptions options = po.as(ReadDataOptions.class);
      long count = 0;

      List<RowRange> ranges = getRangesForTimeIndex(timeOffsetIndex, getMaxInput(options));
      if (ranges.size() == 0) {
        return;
      }

      try {
        // Scan with a filter that will only return the first key from each row. This filter is used
        // to more efficiently perform row count operations.
        Filter rangeFilters = new MultiRowRangeFilter(ranges);
        FilterList firstKeyFilterWithRanges = new FilterList(new FirstKeyOnlyFilter(),
            rangeFilters);
        Scan scan =
            new Scan()
                .addFamily(Bytes.toBytes(COLUMN_FAMILY))
                .setFilter(firstKeyFilterWithRanges);

        Table table = getConnection().getTable(TableName.valueOf(options.getBigtableTableId()));
        ResultScanner imageData = table.getScanner(scan);

        for (Result row : imageData) {
          count++;
        }
      } catch (Exception e) {
        System.out.println("Error reading.");
        e.printStackTrace();
      }
      System.out.printf("got %d rows\n", count);
    }

    /**
     * Download the image data as a grid of weights and store them in a 2D array.
     */
    private void downloadImageData(String artUrl) {
      try {
        ReadableByteChannel chan =
            FileSystems.open(
                FileSystems.matchNewResource(artUrl, false /* is_directory */));
        InputStream is = Channels.newInputStream(chan);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        String line;
        while ((line = br.readLine()) != null) {
          imageData.add(
              Arrays.stream(line.split(","))
                  .map(Float::valueOf)
                  .collect(Collectors.toList()));
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    /**
     * Generates an array with the rowkeys that were loaded into the specified Bigtable. This is
     * used to create the correct intervals for scanning equal sections of rowkeys. Since Bigtable
     * sorts keys lexicographically if we just used standard intervals, each section would have
     * different sizes.
     */
    private void generateRowkeys(long maxInput) {
      int maxLength = ("" + maxInput).length();
      for (int i = 0; i < maxInput + 1; i++) {
        // Make each number the same length by padding with 0s.
        String paddedRowkey = String.format("%0" + maxLength + "d", i);
        String reversedRowkey = new StringBuilder(paddedRowkey).reverse().toString();
        keys[i] = "" + reversedRowkey;
      }
      Arrays.sort(keys);
    }


    /**
     * Get the ranges to scan for the given time index.
     */
    private List<RowRange> getRangesForTimeIndex(@Element Integer timeOffsetIndex, long maxInput) {
      List<RowRange> ranges = new ArrayList<>();

      int numRows = imageData.size();
      int numCols = imageData.get(0).size();
      int rowHeight = (int) (maxInput / numRows);
      int columnIndex = timeOffsetIndex % numCols;

      for (int i = 0; i < imageData.size(); i++) {
        // To generate shading, only scan each pixel with a probability based on it's weight.
        if (Math.random() <= imageData.get(i).get(columnIndex)) {
          // Get the indexes of the rowkeys for the interval.
          long startKeyI = maxInput - (i + 1) * rowHeight;
          long endKeyI = startKeyI + rowHeight;

          String startKey = keys[Math.toIntExact(startKeyI)];
          String endKey = keys[Math.toIntExact(endKeyI)];
          ranges.add(
              new RowRange(
                  Bytes.toBytes("" + startKey), true,
                  Bytes.toBytes("" + endKey), false));
        }
      }
      return ranges;
    }
  }

  /**
   * Get the start time floored to 15 a minute interval
   */
  private static long getStartTime() {
    Date date = new Date();
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(date);

    int remainder = calendar.get(Calendar.MINUTE) % 15;
    calendar.add(Calendar.MINUTE, -remainder);
    return calendar.getTime().getTime();
  }


  public interface ReadDataOptions extends BigtableOptions {

    @Description("The number of gigabytes written using the load data script.")
    @Default.Long(40)
    long getGigabytesWritten();

    void setGigabytesWritten(long gigabytesWritten);

    @Description("The number of megabytes per row written using the load data script.")
    @Default.Long(5)
    long getMegabytesPerRow();

    void setMegabytesPerRow(long megabytesPerRow);

    @Description("The file containing the pixels to draw.")
    @Default.String("gs://public-examples-testing-bucket/mona2.txt")
    String getFilePath();

    void setFilePath(String filePath);
  }

  public interface BigtableOptions extends DataflowPipelineOptions {

    @Description("The Bigtable project ID, this can be different than your Dataflow project")
    @Default.String("bigtable-project")
    String getBigtableProjectId();

    void setBigtableProjectId(String bigtableProjectId);

    @Description("The Bigtable instance ID")
    @Default.String("bigtable-instance")
    String getBigtableInstanceId();

    void setBigtableInstanceId(String bigtableInstanceId);

    @Description("The Bigtable table ID in the instance.")
    @Default.String("bigtable-table")
    String getBigtableTableId();

    void setBigtableTableId(String bigtableTableId);
  }
}