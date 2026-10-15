package rip.ysm.imagestream.webp;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.io.IOException;
import java.util.Locale;

public class WebpImageWriterSpi extends ImageWriterSpi {

    private static final String VENDOR_NAME = "OpenYSM";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"webp", "WEBP"};
    private static final String[] SUFFIXES = {"webp"};
    private static final String[] MIME_TYPES = {"image/webp"};
    private static final String WRITER_CLASS_NAME = "rip.ysm.imagestream.webp.WebpImageWriter";
    private static final String[] READER_SPI_NAMES = {"rip.ysm.imagestream.webp.WebpImageReaderSpi"};

    public WebpImageWriterSpi() {
        super(
                VENDOR_NAME,
                VERSION,
                FORMAT_NAMES,
                SUFFIXES,
                MIME_TYPES,
                WRITER_CLASS_NAME,
                new Class[]{ImageOutputStream.class},
                READER_SPI_NAMES,
                false,
                null, null, null, null,
                false,
                null, null, null, null
        );
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return true;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new WebpImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "WebP Image Writer";
    }
}
