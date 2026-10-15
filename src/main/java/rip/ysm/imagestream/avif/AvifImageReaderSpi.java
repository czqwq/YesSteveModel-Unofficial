package rip.ysm.imagestream.avif;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

public class AvifImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR_NAME = "OpenYSM";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"avif", "AVIF"};
    private static final String[] SUFFIXES = {"avif"};
    private static final String[] MIME_TYPES = {"image/avif"};
    private static final String READER_CLASS_NAME = "rip.ysm.imagestream.avif.AvifImageReader";
    private static final String[] WRITER_SPI_NAMES = {"rip.ysm.imagestream.avif.AvifImageWriterSpi"};

    public AvifImageReaderSpi() {
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
            byte[] header = new byte[12];
            int bytesRead = iis.read(header);
            if (bytesRead < 12) {
                return false;
            }
            if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
                return false;
            }
            String brand = new String(header, 8, 4, "ASCII");
            return "avif".equals(brand) || "avis".equals(brand);
        } finally {
            iis.reset();
        }
    }

    @Override
    public AvifImageReader createReaderInstance(Object extension) {
        return new AvifImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "AVIF Image Reader";
    }
}
