package rip.ysm.imagestream.avif;

import rip.ysm.imagestream.avif.dec.AStruct;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

public class AvifDecoder {
   public BufferedImage read(byte[] data) throws Exception {
      byte[] av1Data = extractAV1FromISO(data);
      if (av1Data != null) {
         return AStruct.getDecodedImage(av1Data);
      }
      return AStruct.getDecodedImage(data);
   }

   public BufferedImage read(File imageFile) throws Exception {
      return read(Files.readAllBytes(imageFile.toPath()));
   }

   private static byte[] extractAV1FromISO(byte[] data) {
      if (data.length < 8) return null;
      int pos = 0;
      boolean isFtyp = pos + 8 <= data.length &&
              data[pos + 4] == 'f' && data[pos + 5] == 't' &&
              data[pos + 6] == 'y' && data[pos + 7] == 'p';
      if (!isFtyp) return null;
      while (pos + 8 <= data.length) {
         int boxSize = (data[pos] & 0xFF) << 24 | (data[pos + 1] & 0xFF) << 16 |
                       (data[pos + 2] & 0xFF) << 8 | (data[pos + 3] & 0xFF);
         if (boxSize < 8 || pos + boxSize > data.length) break;
         boolean isMdat = data[pos + 4] == 'm' && data[pos + 5] == 'd' &&
                          data[pos + 6] == 'a' && data[pos + 7] == 't';
         if (isMdat) {
            int contentLen = boxSize - 8;
            byte[] content = new byte[contentLen];
            System.arraycopy(data, pos + 8, content, 0, contentLen);
            return content;
         }
         pos += boxSize;
      }
      return null;
   }
}
