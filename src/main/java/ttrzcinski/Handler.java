package ttrzcinski;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

/**
 * @inheritDoc
 */
public class Handler implements RequestHandler<S3Event, String> {

  private static final float MAX_WIDTH = 100;
  private static final float MAX_HEIGHT = 100;
  private static final String JPG_TYPE = "jpg";
  private static final String JPG_MIME = "image/jpeg";
  private static final String PNG_TYPE = "png";
  private static final String PNG_MIME = "image/png";

  /**
   * Handles passed S3 event.
   *
   * @param s3event passed S3 event
   * @param context used context
   * @return OK, if worked, else error
   */
  public String handleRequest(S3Event s3event, Context context) {
    try {
      S3EventNotificationRecord record = s3event.getRecords().get(0);

      String srcBucket = record.getS3().getBucket().getName();

      // Object key may have spaces or unicode non-ASCII characters.
      String srcKey = record.getS3().getObject().getUrlDecodedKey();

      String dstBucket = String.format("%s-resized", srcBucket);
      String dstKey = String.format("resized-%s", srcKey);

      // Sanity check: validate that source and destination are different
      // buckets.
      if (srcBucket.equals(dstBucket)) {
        System.out.println(
            "Destination bucket must not match source bucket."
        );
        return "";
      }

      // Infer the image type.
      Matcher matcher = Pattern.compile(".*\\.([^\\.]*)").matcher(srcKey);
      if (!matcher.matches()) {
        System.out.println("Unable to infer image type for key "
            + srcKey);
        return "";
      }
      String imageType = matcher.group(1);
      if (!(JPG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
        System.out.println("Skipping non-image " + srcKey);
        return "";
      }

      // Download the image from S3 into a stream
      AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
      S3Object s3Object = s3Client.getObject(new GetObjectRequest(
          srcBucket, srcKey));
      InputStream objectData = s3Object.getObjectContent();

      BufferedImage resizedImage = this.drawIt(objectData);

      // Re-encode image to target format
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      ImageIO.write(resizedImage, imageType, os);
      InputStream is = new ByteArrayInputStream(os.toByteArray());
      // Set Content-Length and Content-Type
      ObjectMetadata meta = new ObjectMetadata();
      meta.setContentLength(os.size());
      if (JPG_TYPE.equals(imageType)) {
        meta.setContentType(JPG_MIME);
      }
      if (PNG_TYPE.equals(imageType)) {
        meta.setContentType(PNG_MIME);
      }

      // Uploading to S3 destination bucket
      System.out.println("Writing to: " + dstBucket + "/" + dstKey);
      try {
        s3Client.putObject(dstBucket, dstKey, is, meta);
      } catch (AmazonServiceException e) {
        System.err.println(e.getErrorMessage());
        System.exit(1);
      }
      System.out.printf("Successfully resized %s/%s and uploaded to %s/%s%n", srcBucket, srcKey, dstBucket, dstKey);
      return "Ok";
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private BufferedImage drawIt(InputStream objectData) throws IOException {
    // Read the source image
    BufferedImage srcImage = ImageIO.read(objectData);
    int srcHeight = srcImage.getHeight();
    int srcWidth = srcImage.getWidth();
    // Infer the scaling factor to avoid stretching the image
    // unnaturally
    float scalingFactor = Math.min(MAX_WIDTH / srcWidth, MAX_HEIGHT
        / srcHeight);
    int width = (int) (scalingFactor * srcWidth);
    int height = (int) (scalingFactor * srcHeight);

    BufferedImage resizedImage = new BufferedImage(width, height,
        BufferedImage.TYPE_INT_RGB);
    Graphics2D g = resizedImage.createGraphics();
    // Fill with white before applying semi-transparent (alpha) images
    g.setPaint(Color.white);
    g.fillRect(0, 0, width, height);
    // Simple bilinear resize
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(srcImage, 0, 0, width, height, null);
    g.dispose();

    return resizedImage;
  }
}
