package rip.ysm.imagestream.jpeg;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

public class JpegImageReader extends ImageReader {

    private BufferedImage decoded;

    protected JpegImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        return decoded.getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        return decoded.getHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        ImageTypeSpecifier spec = new ImageTypeSpecifier(decoded.getColorModel(), decoded.getSampleModel());
        return Collections.singletonList(spec).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        return decoded;
    }

    private void ensureDecoded() throws IOException {
        if (decoded != null) {
            return;
        }
        ImageInputStream iis = (ImageInputStream) getInput();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = iis.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        try {
            decoded = new JpegDecoder().read(baos.toByteArray());
        } catch (Exception e) {
            throw new IOException("Failed to decode JPEG image", e);
        }
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        decoded = null;
    }

    private static void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("Only a single image at index 0 is available");
        }
    }
}
