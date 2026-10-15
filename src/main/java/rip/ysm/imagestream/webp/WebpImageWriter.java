package rip.ysm.imagestream.webp;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class WebpImageWriter extends ImageWriter {

    protected WebpImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        BufferedImage bi = (BufferedImage) image.getRenderedImage();
        ImageOutputStream ios = (ImageOutputStream) getOutput();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            new WebpEncoder().write(bi, baos);
        } catch (Exception e) {
            throw new IOException("Failed to encode WebP image", e);
        }
        ios.write(baos.toByteArray());
    }
}
