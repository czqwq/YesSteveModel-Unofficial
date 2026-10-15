package rip.ysm.imagestream.webp;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

public class WebpImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR_NAME = "OpenYSM";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"webp", "WEBP"};
    private static final String[] SUFFIXES = {"webp"};
    private static final String[] MIME_TYPES = {"image/webp"};
    private static final String READER_CLASS_NAME = "rip.ysm.imagestream.webp.WebpImageReader";
    private static final String[] WRITER_SPI_NAMES = {"rip.ysm.imagestream.webp.WebpImageWriterSpi"};

    public WebpImageReaderSpi() {
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
            return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        } finally {
            iis.reset();
        }
    }

    @Override
    public WebpImageReader createReaderInstance(Object extension) {
        return new WebpImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "WebP Image Reader";
    }
}
