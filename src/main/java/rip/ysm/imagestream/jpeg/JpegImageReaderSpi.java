package rip.ysm.imagestream.jpeg;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

public class JpegImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR_NAME = "OpenYSM";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"jpeg-is", "JPEG-IS"};
    private static final String[] SUFFIXES = {"jpg", "jpeg"};
    private static final String[] MIME_TYPES = {"image/jpeg"};
    private static final String READER_CLASS_NAME = "rip.ysm.imagestream.jpeg.JpegImageReader";
    private static final String[] WRITER_SPI_NAMES = {"rip.ysm.imagestream.jpeg.JpegImageWriterSpi"};

    public JpegImageReaderSpi() {
        super(
                VENDOR_NAME,
                VERSION,
                FORMAT_NAMES,
                SUFFIXES,
                MIME_TYPES,
                READER_CLASS_NAME,
                new Class[]{ImageInputStream.class},
                WRITER_SPI_NAMES,
                false,
                null, null, null, null,
                false,
                null, null, null, null
        );
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream)) {
            return false;
        }
        ImageInputStream iis = (ImageInputStream) source;
        iis.mark();
        try {
            byte[] header = new byte[2];
            int bytesRead = iis.read(header);
            if (bytesRead < 2) {
                return false;
            }
            return (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8;
        } finally {
            iis.reset();
        }
    }

    @Override
    public JpegImageReader createReaderInstance(Object extension) {
        return new JpegImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG Image Reader (ImageStream)";
    }
}
