package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.ByteWriter;
import rip.ysm.imagestream.utility.DataWriter;
import rip.ysm.imagestream.utility.PixGet;
import rip.ysm.imagestream.utility.WriterByteLittle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import rip.ysm.imagestream.internal.LogWriter;

public final class EVP8L {
   private static final int RED = 0;
   private static final int GREEN = 1;
   private static final int BLUE = 2;
   private static final int ALPHA = 3;
   private static final int DELTA_RED = 4;
   private static final int DELTA_GREEN = 5;
   private static final int DELTA_BLUE = 6;
   private static final int DELTA_ALPHA = 7;
   private static final int RED_MINUS_GREEN = 8;
   private static final int BLUE_MINUS_GREEN = 9;
   private static final int DELTA_RED_MINUS_DELTA_GREEN = 10;
   private static final int DELTA_BLUE_MINUS_DELTA_GREEN = 11;
   private static final int PALETTE = 12;
   private static final int PREDICT_COUNT = 8;
   private static final int T_NOTHING = 0;
   private static final int T_PALETTE = 1;
   private static final int T_SUBGREEN = 2;
   private static final int T_PREDICT = 3;
   private static final int T_SUBGREEN_PREDICT = 4;
   private static final int T_COUNT = 5;
   private static final int[] DISTANCE_CODES = new int[]{
      96,
      73,
      55,
      39,
      23,
      13,
      5,
      1,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      101,
      78,
      58,
      42,
      26,
      16,
      8,
      2,
      0,
      3,
      9,
      17,
      27,
      43,
      59,
      79,
      102,
      86,
      62,
      46,
      32,
      20,
      10,
      6,
      4,
      7,
      11,
      21,
      33,
      47,
      63,
      87,
      105,
      90,
      70,
      52,
      37,
      28,
      18,
      14,
      12,
      15,
      19,
      29,
      38,
      53,
      71,
      91,
      110,
      99,
      82,
      66,
      48,
      35,
      30,
      24,
      22,
      25,
      31,
      36,
      49,
      67,
      83,
      100,
      115,
      108,
      94,
      76,
      64,
      50,
      44,
      40,
      34,
      41,
      45,
      51,
      65,
      77,
      95,
      109,
      118,
      113,
      103,
      92,
      80,
      68,
      60,
      56,
      54,
      57,
      61,
      69,
      81,
      93,
      104,
      114,
      119,
      116,
      111,
      106,
      97,
      88,
      84,
      74,
      72,
      75,
      85,
      89,
      98,
      107,
      112,
      117
   };
   private static final int[] CODE_LENGTH_ORDER = new int[]{17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
   private static final int MAX_TRANSFORMBITS = 9;

   private EVP8L() {
   }

   public static void encode(BufferedImage image, OutputStream out) throws IOException {
      int[] pixels = getPixels(image);
      EBit b = new EBit();
      writeImageBitstream(b, pixels, image.getWidth(), image.getHeight());
      b.align();
      byte[] compressed = b.byteBuffer.toArray();
      byte[] temp = new byte[compressed.length + 20];
      DataWriter w = new WriterByteLittle(temp);
      writeContainer(w, compressed);
      out.write(temp);
   }

   private static void writeImageBitstream(EBit b, int[] image, int iw, int ih) {
      Analysis analysis = analyzeImage(image);
      writeHeader(b, iw, ih, analysis.hasAlpha);
      if (analysis.paletteOrNull != null) {
         image = paletteTransform(b, image, iw, ih, analysis.paletteOrNull);
      }

      if (analysis.useSubtractGreen) {
         image = subtractGreenTransform(b, image);
      }

      if (analysis.usePredict) {
         image = predictTransform(b, image, iw, ih);
      }

      b.putBits(0, 1);
      writeImageData(b, image, iw, ih, true, analysis.colorCacheBits);
   }

   private static void writeContainer(DataWriter w, byte[] data) throws IOException {
      w.write("RIFF".getBytes());
      w.putU32(12 + data.length);
      w.write("WEBP".getBytes());
      w.write("VP8L".getBytes());
      w.putU32(data.length);
      w.write(data);
   }

   private static void writeHeader(EBit b, int iw, int ih, boolean hasAlpha) {
      b.putBits(47, 8);
      b.putBits(iw - 1, 14);
      b.putBits(ih - 1, 14);
      b.putBits(hasAlpha ? 1 : 0, 1);
      b.putBits(0, 3);
   }

   private static void writeImageData(EBit b, int[] image, int iw, int ih, boolean isRecursive, int colCacheBits) {
      if (colCacheBits > 0) {
         if (colCacheBits > 11) {
            LogWriter.writeLog("color cache bits exceeds");
         }

         b.putBits(1, 1);
         b.putBits(colCacheBits, 4);
      } else {
         b.putBits(0, 1);
      }

      if (isRecursive) {
         b.putBits(0, 1);
      }

      int[] encodedLen = new int[]{0};
      int[] encoded = encodeImageData(image, iw, ih, colCacheBits, encodedLen);
      int eLen = encodedLen[0];
      int[][] histos = new int[][]{new int[280 + (colCacheBits > 0 ? 1 << colCacheBits : 0)], new int[256], new int[256], new int[256], new int[40]};

      for (int i = 0; i < eLen; i++) {
         histos[0][encoded[i]]++;
         if (encoded[i] < 256) {
            histos[1][encoded[i + 1]]++;
            histos[2][encoded[i + 2]]++;
            histos[3][encoded[i + 3]]++;
            i += 3;
         } else if (encoded[i] < 280) {
            histos[4][encoded[i + 2]]++;
            i += 3;
         }
      }

      Code[][] codess = new Code[5][];

      for (int ix = 0; ix < 5; ix++) {
         Code[] codes = buildCodes(histos[ix], 16);
         writeCodeLengths(b, codes);
         codess[ix] = codes;
      }

      for (int ix = 0; ix < eLen; ix++) {
         b.putCode(codess[0][encoded[ix]]);
         if (encoded[ix] < 256) {
            b.putCode(codess[1][encoded[ix + 1]]);
            b.putCode(codess[2][encoded[ix + 2]]);
            b.putCode(codess[3][encoded[ix + 3]]);
            ix += 3;
         } else if (encoded[ix] < 280) {
            b.putBits(encoded[ix + 1], extraBits(encoded[ix] - 256));
            b.putCode(codess[4][encoded[ix + 2]]);
            b.putBits(encoded[ix + 3], extraBits(encoded[ix + 2]));
            ix += 3;
         }
      }
   }

   private static int[] encodeImageData(int[] image, int iw, int ih, int colorCacheBits, int[] encodedLen) {
      ChainTable chainTable = new ChainTable();
      ColorCache colorCache = new ColorCache(colorCacheBits);
      int pixelCount = image.length;
      int maxLen = Math.min(4096, pixelCount);
      int[] encoded = new int[pixelCount * 4];
      int[] lengthExtra = new int[]{0};
      int[] distanceExtra = new int[]{0};
      int dim = iw * ih;
      int eLen = 0;

      for (int pix = 0; pix < pixelCount; pix++) {
         int argb = image[pix];
         if (pix + 2 < pixelCount && dim == pixelCount) {
            Chain chain = chainTable.get(argb, image[pix + 1], image[pix + 2]);
            int longestIndex = 0;
            int longestLength = 0;

            for (int i = 0; i < 100 && chain != null && pix - chain.index <= 1048456; i++) {
               int length = findMatchLen(image, pixelCount, pix, chain.index, maxLen);
               if (length > longestLength) {
                  longestIndex = chain.index;
                  longestLength = length;
               }

               chain = chain.next;
            }

            if (longestLength > 2) {
               int distanceCode = distanceCode(iw, pix - longestIndex);
               int lengthSymbol = prefixCode(longestLength, lengthExtra);
               int distanceSymbol = prefixCode(distanceCode, distanceExtra);
               if (colorCache.isPresent) {
                  for (int i = 0; i < longestLength; i++) {
                     colorCache.insert(image[pix + i]);
                  }
               }

               encoded[eLen++] = lengthSymbol + 256;
               encoded[eLen++] = lengthExtra[0];
               encoded[eLen++] = distanceSymbol;
               encoded[eLen++] = distanceExtra[0];
               pix += longestLength - 1;
               continue;
            }

            chainTable.add(argb, image[pix + 1], image[pix + 2], pix);
         }

         int[] colorCacheIndex = new int[]{0};
         if (colorCache.isPresent && colorCache.lookup(argb, colorCacheIndex)) {
            encoded[eLen++] = colorCacheIndex[0] + 256 + 24;
         } else {
            encoded[eLen++] = argb >> 8 & 0xFF;
            encoded[eLen++] = argb >> 16 & 0xFF;
            encoded[eLen++] = argb & 0xFF;
            encoded[eLen++] = argb >>> 24;
            if (colorCache.isPresent) {
               colorCache.insert(argb);
            }
         }
      }

      encodedLen[0] = eLen;
      return encoded;
   }

   private static int findMatchLen(int[] image, int dim, int pix, int matchIndex, int maxLength) {
      int i;
      for (i = 0; pix + i < dim && i < maxLength; i++) {
         int maxP = matchIndex + i;
         if (image[pix + i] != image[maxP]) {
            break;
         }
      }

      return i;
   }

   private static int distanceCode(int iw, int dist) {
      int distY = dist / iw;
      int distX = dist - distY * iw;
      if (distX <= 8 && distY < 8) {
         return DISTANCE_CODES[distY * 16 + 8 - distX] + 1;
      } else {
         return distX > iw - 8 && distY < 7 ? DISTANCE_CODES[(distY + 1) * 16 + 8 + (iw - distX)] + 1 : dist + 120;
      }
   }

   private static int prefixCode(int n, int[] extra) {
      extra[0] = 0;
      if (n <= 5) {
         return n - 1;
      } else {
         int rem = n - 1;

         int shift;
         for (shift = 0; rem > 3; shift++) {
            rem >>= 1;
         }

         switch (rem) {
            case 2:
               extra[0] = n - (2 << shift) - 1;
               return 2 + 2 * shift;
            case 3:
               extra[0] = n - (3 << shift) - 1;
               return 3 + 2 * shift;
            default:
               return 0;
         }
      }
   }

   private static int extraBits(int prefixCode) {
      return prefixCode < 4 ? 0 : prefixCode - 2 >> 1;
   }

   private static int[] getPixels(BufferedImage image) {
      PixGet pg = Access.getPixGet(image);
      int w = image.getWidth();
      int h = image.getHeight();
      int[] pixels = new int[w * h];
      int p = 0;

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            pixels[p++] = pg.getARGB(x, y);
         }
      }

      return pixels;
   }

   private static void writeCodeLengths(EBit b, Code[] codes) {
      List<Code> simpleCodes = new ArrayList<>();
      boolean allSimple = true;

      for (Code code : codes) {
         if (code.present) {
            if (code.len > 1 || code.symbol > 255) {
               allSimple = false;
               break;
            }

            simpleCodes.add(code);
         }
      }

      if (allSimple) {
         writeSimpleCodeLengths(b, simpleCodes);
      } else {
         writeNormalCodeLengths(b, codes);
      }
   }

   private static void writeSimpleCodeLengths(EBit b, List<Code> codes) {
      b.putBits(1, 1);
      if (codes.isEmpty()) {
         b.putBits(0, 3);
      } else {
         b.putBits(codes.size() - 1, 1);
         if (codes.get(0).symbol <= 1) {
            b.putBits(0, 1);
            b.putBits(codes.get(0).symbol, 1);
         } else {
            b.putBits(1, 1);
            b.putBits(codes.get(0).symbol, 8);
         }

         if (codes.size() > 1) {
            b.putBits(codes.get(1).symbol, 8);
         }
      }
   }

   private static void writeNormalCodeLengths(EBit b, Code[] codes) {
      List<Integer> encodedLengths = encodeCodeLengths(codes);
      int[] lengthHisto = new int[19];

      for (int i = 0; i < encodedLengths.size(); i++) {
         int sym = encodedLengths.get(i);
         lengthHisto[sym]++;
         if (sym >= 16) {
            i++;
         }
      }

      Code[] lengthCodes = buildCodes(lengthHisto, 7);
      int lengthCodeCount = 0;

      for (int ix = 0; ix < 19; ix++) {
         if (lengthHisto[CODE_LENGTH_ORDER[ix]] > 0) {
            lengthCodeCount = ix + 1;
         }
      }

      if (lengthCodeCount < 4) {
         lengthCodeCount = 4;
      }

      b.putBits(0, 1);
      b.putBits(lengthCodeCount - 4, 4);

      for (int ixx = 0; ixx < lengthCodeCount; ixx++) {
         b.putBits(lengthCodes[CODE_LENGTH_ORDER[ixx]].len, 3);
      }

      b.putBits(0, 1);

      for (int ixx = 0; ixx < encodedLengths.size(); ixx++) {
         int sym = encodedLengths.get(ixx);
         b.putCode(lengthCodes[sym]);
         switch (sym) {
            case 16:
               b.putBits(encodedLengths.get(++ixx), 2);
               break;
            case 17:
               b.putBits(encodedLengths.get(++ixx), 3);
               break;
            case 18:
               b.putBits(encodedLengths.get(++ixx), 7);
         }
      }
   }

   private static List<Integer> encodeCodeLengths(Code[] codes) {
      List<Integer> lengthCodes = new ArrayList<>();
      int lastLength = 8;

      for (int sym = 0; sym < codes.length; sym++) {
         if (codes[sym].len == 0) {
            int streak = 1;

            while (streak < 138 && sym + streak < codes.length && codes[sym + streak].len == 0) {
               streak++;
            }

            if (streak >= 11) {
               lengthCodes.add(18);
               lengthCodes.add(streak - 11);
               sym += streak - 1;
            } else if (streak >= 3) {
               lengthCodes.add(17);
               lengthCodes.add(streak - 3);
               sym += streak - 1;
            } else {
               lengthCodes.add(0);
            }
         } else {
            if (codes[sym].len == lastLength) {
               int streak = 1;

               while (streak < 6 && sym + streak < codes.length && codes[sym + streak].len == lastLength) {
                  streak++;
               }

               if (streak >= 3) {
                  lengthCodes.add(16);
                  lengthCodes.add(streak - 3);
                  sym += streak - 1;
                  continue;
               }
            } else {
               lastLength = codes[sym].len;
            }

            lengthCodes.add(codes[sym].len);
         }
      }

      return lengthCodes;
   }

   private static Code[] buildCodes(int[] histo, int maxLength) {
      Code[] codes = new Code[histo.length];

      for (int sym = 0; sym < histo.length; sym++) {
         codes[sym] = new Code(sym);
      }

      Node tree = buildTree(histo, maxLength);
      if (!tree.isBranch) {
         int singletonSym = tree.leafSymbol;
         codes[singletonSym] = new Code(singletonSym, 0, 0);
         return codes;
      } else {
         assignCodeLengths(tree, 0, codes);
         Code[] sorted = new Code[codes.length];
         System.arraycopy(codes, 0, sorted, 0, sorted.length);
         Arrays.sort(sorted, new CodeComparer());
         int bits = 0;
         int length = 0;

         for (Code code : sorted) {
            if (code.present) {
               bits <<= code.len - length;
               length = code.len;
               codes[code.symbol] = new Code(code.symbol, bits, code.len);
               bits++;
            }
         }

         return codes;
      }
   }

   private static Node buildTree(int[] histo, int maxLength) {
      int minWeight = sum(histo) >> maxLength - 2;
      EStack heap = new EStack(new NodeComparer());

      for (int sym = 0; sym < histo.length; sym++) {
         int weight = histo[sym];
         if (weight != 0) {
            if (weight < minWeight) {
               weight = minWeight;
            }

            heap.add(new Node(sym, weight));
         }
      }

      while (heap.arr.size() > 1) {
         Node n1 = heap.remove();
         Node n2 = heap.remove();
         heap.add(new Node(n1, n2));
      }

      return !heap.arr.isEmpty() ? heap.min() : new Node(0, 0);
   }

   private static void assignCodeLengths(Node node, int depth, Code[] codes) {
      if (node.isBranch) {
         assignCodeLengths(node.left, depth + 1, codes);
         assignCodeLengths(node.right, depth + 1, codes);
      } else {
         codes[node.leafSymbol] = new Code(node.leafSymbol, 0, depth);
      }
   }

   private static Analysis analyzeImage(int[] pixels) {
      boolean[] hasAlpha = new boolean[]{false};
      Palette palette = createPalette(pixels);
      int[][] histos = computeHisto(pixels, palette, hasAlpha);
      double[] entropies = new double[histos.length];

      for (int i = 0; i < entropies.length; i++) {
         entropies[i] = entropy(histos[i]);
      }

      double[] transformEntropies = computeTransformEntropies(entropies);
      int best = 0;

      for (int i = 1; i < 5; i++) {
         if ((i != 1 || palette != null) && transformEntropies[i] < transformEntropies[best]) {
            best = i;
         }
      }

      Analysis analysis = new Analysis();
      analysis.hasAlpha = hasAlpha[0];
      analysis.paletteOrNull = best == 1 ? palette : null;
      analysis.useSubtractGreen = best == 2 || best == 4;
      analysis.usePredict = best == 3 || best == 4;
      analysis.colorCacheBits = 0;
      return analysis;
   }

   private static int sum(int[] bins) {
      int sum = 0;

      for (int x : bins) {
         sum += x;
      }

      return sum;
   }

   private static double entropy(int[] bins) {
      double sum = sum(bins);
      if (sum == 0.0) {
         return 0.0;
      } else {
         double logSum = Math.log(sum);
         double sumLogs = 0.0;

         for (int x : bins) {
            if (x != 0) {
               sumLogs += x * (Math.log(x) - logSum);
            }
         }

         return -sumLogs / sum / Math.log(2.0);
      }
   }

   private static int[][] computeHisto(int[] pixels, Palette palette, boolean[] hasAlpha) {
      int[][] histos = new int[13][256];
      long previous = -16777216L;

      for (int i = 0; i < pixels.length; i++) {
         int v = pixels[i];
         int a = v >>> 24;
         int r = v >> 16 & 0xFF;
         int g = v >> 8 & 0xFF;
         int b = v & 0xFF;
         if (a != 255) {
            hasAlpha[0] = true;
         }

         long vl = v & 4294967295L;
         int d = (int)(vl - previous);
         previous = vl;
         histos[0][r]++;
         histos[1][g]++;
         histos[2][b]++;
         histos[3][a]++;
         int da = d >>> 24;
         int dr = d >> 16 & 0xFF;
         int dg = d >> 8 & 0xFF;
         int db = d & 0xFF;
         histos[4][dr]++;
         histos[5][dg]++;
         histos[6][db]++;
         histos[7][da]++;
         histos[8][r - g & 0xFF]++;
         histos[9][b - g & 0xFF]++;
         histos[10][dr - dg & 0xFF]++;
         histos[11][db - dg & 0xFF]++;
         if (palette != null) {
            histos[12][palette.indices.get(v)]++;
         }
      }

      return histos;
   }

   private static double[] computeTransformEntropies(double[] entropies) {
      return new double[]{
         entropies[0] + entropies[1] + entropies[2] + entropies[3],
         entropies[12],
         entropies[8] + entropies[1] + entropies[9] + entropies[3],
         entropies[4] + entropies[5] + entropies[6] + entropies[7],
         entropies[10] + entropies[5] + entropies[11] + entropies[7]
      };
   }

   private static Palette createPalette(int[] pixels) {
      Palette palette = new Palette();

      for (int v : pixels) {
         if (!palette.indices.containsKey(v)) {
            palette.indices.put(v, palette.colors.size());
            palette.colors.add(v);
            if (palette.colors.size() > 256) {
               return null;
            }
         }
      }

      return palette;
   }

   private static int deduct(int ip, int pp) {
      int a = (ip >>> 24) - (pp >>> 24) & 0xFF;
      int r = (ip >> 16 & 0xFF) - (pp >> 16 & 0xFF) & 0xFF;
      int g = (ip >> 8 & 0xFF) - (pp >> 8 & 0xFF) & 0xFF;
      int b = (ip & 0xFF) - (pp & 0xFF) & 0xFF;
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static int[] paletteTransform(EBit b, int[] image, int iw, int ih, Palette palette) {
      b.putBits(1, 1);
      b.putBits(3, 2);
      writePalette(b, palette);
      int pc = palette.colors.size();
      int packSize = pc <= 2 ? 8 : (pc <= 4 ? 4 : (pc <= 16 ? 2 : 1));
      int packedWidth = (iw + packSize - 1) / packSize;
      int[] palettized = new int[packedWidth * ih];

      for (int y = 0; y < ih; y++) {
         for (int i = 0; i < packedWidth; i++) {
            int pack = 0;

            for (int j = 0; j < packSize; j++) {
               int x = i * packSize + j;
               if (x >= iw) {
                  break;
               }

               int colorIndex = palette.indices.get(image[x + y * iw]);
               pack |= colorIndex << j * (8 / packSize);
            }

            palettized[i + packedWidth * y] = 0xFF000000 | pack << 8;
         }
      }

      return palettized;
   }

   private static void writePalette(EBit b, Palette palette) {
      int iw = palette.colors.size();
      int ih = 1;
      int[] image = new int[iw * 1];

      for (int i = 0; i < iw; i++) {
         image[i] = i == 0 ? palette.colors.get(0) : deduct(palette.colors.get(i), palette.colors.get(i - 1));
      }

      b.putBits(iw - 1, 8);
      writeImageData(b, image, iw, 1, false, 0);
   }

   private static int[] subtractGreenTransform(EBit bw, int[] image) {
      bw.putBits(1, 1);
      bw.putBits(2, 2);

      for (int i = 0; i < image.length; i++) {
         int v = image[i];
         int a = v >>> 24;
         int r = v >> 16 & 0xFF;
         int g = v >> 8 & 0xFF;
         int b = v & 0xFF;
         int r_g = r - g;
         int b_g = b - g;
         v = a << 24 | (r_g & 0xFF) << 16 | g << 8 | b_g & 0xFF;
         image[i] = v;
      }

      return image;
   }

   private static int[] predictTransform(EBit b, int[] image, int iw, int ih) {
      b.putBits(1, 1);
      b.putBits(0, 2);
      int tileBits = 9;
      int tileSize = 512;
      int blockedWidth = (iw + 512 - 1) / 512;
      int blockedHeight = (ih + 512 - 1) / 512;
      b.putBits(7, 3);
      int[] blocks = new int[blockedWidth * blockedHeight];
      int[] residuals = new int[iw * ih];
      int[][] accumHistos = new int[4][256];

      for (int y = 0; y < blockedHeight; y++) {
         for (int x = 0; x < blockedWidth; x++) {
            int bestPrediction = 0;
            double bestEntropy = predictEntropy(image, iw, ih, 9, x, y, 0, accumHistos);

            for (int i = 1; i < 8; i++) {
               double entropy = predictEntropy(image, iw, ih, 9, x, y, i, accumHistos);
               if (entropy < bestEntropy) {
                  bestPrediction = i;
                  bestEntropy = entropy;
               }
            }

            blocks[x + y * blockedWidth] = 0xFF000000 | bestPrediction << 8;
            predictBlock(image, iw, ih, residuals, 9, x, y, bestPrediction, accumHistos);
         }
      }

      writeImageData(b, blocks, blockedWidth, blockedHeight, false, 0);
      return residuals;
   }

   private static double predictEntropy(int[] image, int iw, int height, int tileBits, int tileX, int tileY, int prediction, int[][] histos) {
      int maxX = Math.min(tileX + 1 << tileBits, iw);
      int maxY = Math.min(tileY + 1 << tileBits, height);

      for (int x = tileX << tileBits; x < maxX; x++) {
         for (int y = tileY << tileBits; y < maxY; y++) {
            int ip = image[x + y * iw];
            int pp = predict(image, iw, x, y, prediction);
            int da = (ip >>> 24) - (pp >>> 24) & 0xFF;
            int dr = (ip >> 16 & 0xFF) - (pp >> 16 & 0xFF) & 0xFF;
            int dg = (ip >> 8 & 0xFF) - (pp >> 8 & 0xFF) & 0xFF;
            int db = (ip & 0xFF) - (pp & 0xFF) & 0xFF;
            histos[0][dr]++;
            histos[1][dg]++;
            histos[2][db]++;
            histos[3][da]++;
         }
      }

      double sum = 0.0;

      for (int[] histo : histos) {
         sum += entropy(histo);
      }

      return sum;
   }

   private static void predictBlock(int[] image, int iw, int ih, int[] residuals, int tileBits, int tileX, int tileY, int prediction, int[][] histos) {
      int maxX = Math.min(tileX + 1 << tileBits, iw);
      int maxY = Math.min(tileY + 1 << tileBits, ih);

      for (int x = tileX << tileBits; x < maxX; x++) {
         for (int y = tileY << tileBits; y < maxY; y++) {
            int ip = image[x + y * iw];
            int pp = predict(image, iw, x, y, prediction);
            int da = (ip >>> 24) - (pp >>> 24) & 0xFF;
            int dr = (ip >> 16 & 0xFF) - (pp >> 16 & 0xFF) & 0xFF;
            int dg = (ip >> 8 & 0xFF) - (pp >> 8 & 0xFF) & 0xFF;
            int db = (ip & 0xFF) - (pp & 0xFF) & 0xFF;
            histos[0][dr]++;
            histos[1][dg]++;
            histos[2][db]++;
            histos[3][da]++;
            residuals[x + y * iw] = da << 24 | dr << 16 | dg << 8 | db;
         }
      }
   }

   private static int predict(int[] image, int iw, int x, int y, int pred) {
      if (x == 0 && y == 0) {
         return -16777216;
      } else if (x == 0) {
         return image[x + (y - 1) * iw];
      } else if (y == 0) {
         return image[x - 1 + iw * y];
      } else {
         int i = y * iw + x;
         int top = image[i - iw];
         int left = image[i - 1];
         int topLeft = image[i - iw - 1];
         int topRight = image[i - iw + 1];
         return predictSelect(pred, top, left, topLeft, topRight);
      }
   }

   private static int predictSelect(int type, int top, int left, int topLeft, int topRight) {
      switch (type) {
         case 0:
            return -16777216;
         case 1:
            return left;
         case 2:
            return top;
         case 3:
            return topRight;
         case 4:
            return topLeft;
         case 5:
            return average3(left, top, topRight);
         case 6:
            return average2(left, topLeft);
         case 7:
            return average2(left, top);
         case 8:
            return average2(topLeft, top);
         case 9:
            return average2(top, topRight);
         default:
            return 0;
      }
   }

   private static int average2(int a, int b) {
      int xa = (a >>> 24) + (b >>> 24) >> 1;
      int xr = (a >> 16 & 0xFF) + (b >> 16 & 0xFF) >> 1;
      int xg = (a >> 8 & 0xFF) + (b >> 8 & 0xFF) >> 1;
      int xb = (a & 0xFF) + (b & 0xFF) >> 1;
      return xa << 24 | xr << 16 | xg << 8 | xb;
   }

   private static int average3(int a0, int a1, int a2) {
      return average2(average2(a0, a2), a1);
   }

   private static class Analysis {
      private boolean hasAlpha;
      private boolean useSubtractGreen;
      private boolean usePredict;
      private Palette paletteOrNull;
      private int colorCacheBits;
   }

   private static class Chain {
      private Chain next;
      private int index;
      private final int c1;
      private final int c2;
      private final int c3;

      Chain(Chain next, int index, int c1, int c2, int c3) {
         this.next = next;
         this.index = index;
         this.c1 = c1;
         this.c2 = c2;
         this.c3 = c3;
      }

      boolean equals(int t1, int t2, int t3) {
         return this.c1 == t1 && this.c2 == t2 && this.c3 == t3;
      }
   }

   private static class ChainTable {
      private final Chain[] chains = new Chain[16];
      private int count;

      private Chain get(int a1, int a2, int a3) {
         for (Chain chain : this.chains) {
            if (chain != null && chain.equals(a1, a2, a3)) {
               return chain;
            }
         }

         return null;
      }

      private void add(int a1, int a2, int a3, int index) {
         for (Chain chain : this.chains) {
            if (chain != null && chain.equals(a1, a2, a3)) {
               this.count = this.count + 1 & 15;
               this.chains[this.count] = new Chain(chain, index, a1, a2, a3);
               return;
            }
         }

         this.count = this.count + 1 & 15;
         this.chains[this.count] = new Chain(null, index, a1, a2, a3);
      }
   }

   private static final class Code {
      private final boolean present;
      private final int symbol;
      private final int bits;
      private final int len;

      private Code(int sym) {
         this.present = false;
         this.symbol = sym;
         this.bits = 0;
         this.len = 0;
      }

      private Code(int sym, int bits, int length) {
         this.present = true;
         this.symbol = sym;
         this.bits = bits;
         this.len = length;
      }
   }

   private static class CodeComparer implements Comparator<Code> {
      public int compare(Code c1, Code c2) {
         return c1.len == c2.len ? c1.symbol - c2.symbol : c1.len - c2.len;
      }
   }

   private static class ColorCache {
      private int bits;
      private int[] arr;
      private final boolean isPresent;

      ColorCache(int bits) {
         this.bits = bits;
         this.arr = new int[1 << bits];
         this.isPresent = bits > 0;
      }

      private boolean lookup(int color, int[] index) {
         if (this.bits <= 0) {
            index[0] = 0;
            return false;
         } else {
            index[0] = this.index(color);
            return this.arr[index[0]] == color;
         }
      }

      private void insert(int color) {
         if (this.bits > 0) {
            this.arr[this.index(color)] = color;
         }
      }

      private int index(int color) {
         return color * 506832829 >> 32 - this.bits;
      }
   }

   private static class EBit {
      private final ByteWriter byteBuffer = new ByteWriter();
      private int buffer;
      private int bufferLen;

      private void putBits(int bits, int count) {
         this.put();
         this.buffer = this.buffer | bits << this.bufferLen;
         this.bufferLen += count;
      }

      private void putCode(Code code) {
         int i = code.len;

         while (i-- > 0) {
            this.putBits((code.bits & 1 << i) == 0 ? 0 : 1, 1);
         }
      }

      private void align() {
         this.bufferLen = this.bufferLen + 7 & -8;
         this.put();
         if (this.byteBuffer.bp % 2 != 0) {
            this.byteBuffer.putU8(0);
         }
      }

      private void put() {
         while (this.bufferLen >= 8) {
            this.byteBuffer.putU8(this.buffer & 0xFF);
            this.buffer >>>= 8;
            this.bufferLen -= 8;
         }
      }
   }

   private static class EStack {
      private final ArrayList<Node> arr = new ArrayList<>();
      private final NodeComparer comparer;

      EStack(NodeComparer comparer) {
         this.comparer = comparer;
      }

      private void add(Node n) {
         this.arr.add(n);
         this.sortUp(this.arr.size() - 1);
      }

      private Node remove() {
         Node min = this.arr.get(0);
         Node temp = this.arr.get(this.arr.size() - 1);
         this.arr.set(0, temp);
         this.arr.remove(this.arr.size() - 1);
         this.sortDown(0);
         return min;
      }

      private Node min() {
         return this.arr.get(0);
      }

      private void sortUp(int idx) {
         if (idx > 0) {
            int parentIdx = (idx - 1) / 2;
            Node elem = this.arr.get(idx);
            Node parent = this.arr.get(parentIdx);
            if (this.comparer.compare(parent, elem) > 0) {
               this.arr.set(parentIdx, elem);
               this.arr.set(idx, parent);
               this.sortUp(parentIdx);
            }
         }
      }

      private void sortDown(int idx) {
         if (idx < this.arr.size()) {
            Node elem = this.arr.get(idx);
            int leftIdx = idx * 2 + 1;
            int rightIdx = idx * 2 + 2;
            if (rightIdx < this.arr.size()) {
               Node left = this.arr.get(leftIdx);
               Node right = this.arr.get(rightIdx);
               if (this.comparer.compare(left, right) < 0) {
                  if (this.comparer.compare(elem, left) > 0) {
                     this.arr.set(leftIdx, elem);
                     this.arr.set(idx, left);
                     this.sortDown(leftIdx);
                  }
               } else if (this.comparer.compare(elem, right) > 0) {
                  this.arr.set(rightIdx, elem);
                  this.arr.set(idx, right);
                  this.sortDown(rightIdx);
               }
            } else if (rightIdx == this.arr.size()) {
               Node left = this.arr.get(leftIdx);
               if (this.comparer.compare(elem, left) > 0) {
                  this.arr.set(idx, left);
                  this.arr.set(leftIdx, elem);
               }
            }
         }
      }
   }

   private static class Node {
      private final boolean isBranch;
      private final int weight;
      private int leafSymbol;
      private Node left;
      private Node right;

      Node(int symbol, int weight) {
         this.isBranch = false;
         this.weight = weight;
         this.leafSymbol = symbol;
      }

      Node(Node left, Node right) {
         this.isBranch = true;
         this.weight = left.weight + right.weight;
         this.left = left;
         this.right = right;
      }
   }

   private static class NodeComparer {
      private int compare(Node n1, Node n2) {
         return n1.weight - n2.weight;
      }
   }

   private static class Palette {
      private final List<Integer> colors = new ArrayList<>();
      private final HashMap<Integer, Integer> indices = new HashMap<>();
   }
}
