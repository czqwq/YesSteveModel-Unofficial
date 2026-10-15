package rip.ysm.imagestream.jpeg;

import rip.ysm.imagestream.jpeg.data.AdobeHolder;
import rip.ysm.imagestream.jpeg.data.Component;
import rip.ysm.imagestream.jpeg.data.Frame;
import rip.ysm.imagestream.jpeg.data.HTree;
import rip.ysm.imagestream.jpeg.data.IndexMap;
import rip.ysm.imagestream.jpeg.data.Info;
import rip.ysm.imagestream.jpeg.data.JFIFHolder;
import rip.ysm.imagestream.jpeg.data.JpegScanner;
import rip.ysm.imagestream.jpeg.data.JpegScannerInts;
import rip.ysm.imagestream.jpeg.jpeg2000.EnumeratedSpace;
import rip.ysm.imagestream.jpeg.lossless.JpegLosslessDecoder;
import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import rip.ysm.imagestream.internal.DCT;
import rip.ysm.imagestream.internal.LogWriter;

public class JpegDecoder {
   private int offset;
   private byte[] data;
   public static final byte[] ZIGZAGORDER = new byte[]{
      0,
      1,
      8,
      16,
      9,
      2,
      3,
      10,
      17,
      24,
      32,
      25,
      18,
      11,
      4,
      5,
      12,
      19,
      26,
      33,
      40,
      48,
      41,
      34,
      27,
      20,
      13,
      6,
      7,
      14,
      21,
      28,
      35,
      42,
      49,
      56,
      57,
      50,
      43,
      36,
      29,
      22,
      15,
      23,
      30,
      37,
      44,
      51,
      58,
      59,
      52,
      45,
      38,
      31,
      39,
      46,
      53,
      60,
      61,
      54,
      47,
      55,
      62,
      63
   };
   private boolean isDimensionOnly;
   private static final double[] LINEARTABLE = new double[]{
      0.0,
      5.099078671483812E-6,
      2.341652914594477E-5,
      5.711967424262048E-5,
      1.0753586531413878E-4,
      1.756627396599306E-4,
      2.6231101790786317E-4,
      3.6816895747529804E-4,
      4.938375903955528E-4,
      6.398522940681874E-4,
      8.066970384487688E-4,
      9.948143285116826E-4,
      0.0012046124392025709,
      0.0014364708566854938,
      0.0016907444812659992,
      0.0019677669462239717,
      0.0022678532876009793,
      0.0025913021261326917,
      0.0029383974750064614,
      0.003309410255569636,
      0.0037045995815722516,
      0.004124213857464306,
      0.004568491725509799,
      0.005037662888651404,
      0.005531948830267067,
      0.006051563447608699,
      0.006596713612400069,
      0.007167599669516945,
      0.007764415882681334,
      0.00838735083453319,
      0.009036587787195637,
      0.009712305008449062,
      0.010414676067820225,
      0.011143870106232967,
      0.01190005208232596,
      0.01268338299809593,
      0.013494020106153163,
      0.014332117100565768,
      0.015197824293007858,
      0.0160912887757068,
      0.017012654572497087,
      0.01796206277912934,
      0.01893965169384597,
      0.0199455569391179,
      0.020979911575335134,
      0.022042846207156467,
      0.023134489083146997,
      0.02425496618926588,
      0.025404401336708245,
      0.026582916244554392,
      0.027790630617634114,
      0.029027662219974663,
      0.030294126944165393,
      0.031590138876941384,
      0.03291581036126018,
      0.03427125205512162,
      0.035656572987358186,
      0.037071880610603986,
      0.03851728085163241,
      0.03999287815923695,
      0.041498775549814924,
      0.04303507465080135,
      0.04460187574208813,
      0.04619927779555348,
      0.0478273785128165,
      0.0494862743613236,
      0.05117606060886511,
      0.052896831356613175,
      0.054648679570766114,
      0.0564316971128772,
      0.05824597476894147,
      0.06009160227730833,
      0.06196866835548361,
      0.0638772607258799,
      0.06581746614057064,
      0.06778937040509939,
      0.06979305840139277,
      0.07182861410982205,
      0.07389612063045584,
      0.07599566020354347,
      0.0781273142292669,
      0.08029116328679532,
      0.08248728715267621,
      0.08471576481859315,
      0.08697667450852045,
      0.08927009369530116,
      0.09159609911667511,
      0.093954766790781,
      0.0963461720311563,
      0.0987703894612559,
      0.10122749302851117,
      0.1037175560179481,
      0.10624065106538386,
      0.10879685017021863,
      0.11138622470783986,
      0.11400884544165421,
      0.11666478253476295,
      0.11935410556129411,
      0.12207688351740562,
      0.12483318483197174,
      0.12762307737696577,
      0.13044662847754965,
      0.1333039049218824,
      0.13619497297065744,
      0.1391198983663792,
      0.14207874634238823,
      0.14507158163164446,
      0.14809846847527652,
      0.15115947063090668,
      0.15425465138075814,
      0.1573840735395531,
      0.16054779946220837,
      0.1637458910513361,
      0.16697840976455555,
      0.1702454166216229,
      0.17354697221138474,
      0.17688313669856154,
      0.18025396983036637,
      0.18365953094296425,
      0.1870998789677774,
      0.19057507243764174,
      0.1940851694928182,
      0.19763022788686505,
      0.20121030499237383,
      0.20482545780657496,
      0.20847574295681537,
      0.21216121670591326,
      0.21588193495739272,
      0.2196379532606032,
      0.22342932681572564,
      0.22725611047866973,
      0.23111835876586506,
      0.23501612585894976,
      0.23894946560935817,
      0.2429184315428129,
      0.24692307686372145,
      0.25096345445948154,
      0.25503961690469756,
      0.25915161646531015,
      0.26329950510264194,
      0.26748333447736095,
      0.2717031559533646,
      0.27595902060158656,
      0.2802509792037277,
      0.28457908225591383,
      0.2889433799722819,
      0.293343922288497,
      0.29778075886520106,
      0.3022539390913962,
      0.30676351208776287,
      0.3113095267099166,
      0.3158920315516027,
      0.320511074947832,
      0.3251667049779579,
      0.329858969468698,
      0.3345879159970995,
      0.3393535918934514,
      0.3441560442441435,
      0.34899531989447563,
      0.3538714654514154,
      0.35878452728630783,
      0.36373455153753737,
      0.3687215841131433,
      0.37374567069339015,
      0.37880685673329295,
      0.38390518746509983,
      0.38904070790073253,
      0.39421346283418507,
      0.39942349684388206,
      0.40467085429499783,
      0.4099555793417369,
      0.4152777159295767,
      0.42063730779747355,
      0.426034398480032,
      0.43146903130964015,
      0.43694124941856927,
      0.4424510957410402,
      0.44799861301525684,
      0.45358384378540717,
      0.45920683040363275,
      0.4648676150319675,
      0.47056623964424527,
      0.4763027460279795,
      0.48207717578621234,
      0.48788957033933605,
      0.4937399709268863,
      0.49962841860930857,
      0.5055549542696977,
      0.511519618615511,
      0.5175224521802562,
      0.5235634953251542,
      0.5296427882407776,
      0.5357603709486635,
      0.5419162833029051,
      0.5481105649917183,
      0.5543432555389864,
      0.5606143943057829,
      0.5669240204918703,
      0.5732721731371815,
      0.5796588911232758,
      0.5860842131747783,
      0.5925481778607952,
      0.5990508235963129,
      0.6055921886435752,
      0.6121723111134431,
      0.6187912289667334,
      0.625448980015543,
      0.6321456019245507,
      0.6388811322123048,
      0.6456556082524908,
      0.6524690672751842,
      0.6593215463680848,
      0.6662130824777358,
      0.6731437124107256,
      0.6801134728348749,
      0.6871224002804077,
      0.6941705311411072,
      0.7012579016754554,
      0.7083845480077606,
      0.7155505061292676,
      0.7227558118992558,
      0.7300005010461207,
      0.7372846091684455,
      0.7446081717360559,
      0.7519712240910621,
      0.7593738014488893,
      0.766815938899294,
      0.7742976714073675,
      0.7818190338145284,
      0.7893800608395011,
      0.7969807870792839,
      0.8046212470101042,
      0.8123014749883624,
      0.8200215052515645,
      0.8277813719192435,
      0.8355811089938695,
      0.8434207503617492,
      0.8513003297939138,
      0.8592198809469983,
      0.8671794373641086,
      0.8751790324756791,
      0.8832186996003203,
      0.8912984719456563,
      0.8994183826091531,
      0.9075784645789368,
      0.9157787507346018,
      0.9240192738480122,
      0.9323000665840907,
      0.9406211615016009,
      0.9489825910539202,
      0.9573843875898034,
      0.9658265833541381,
      0.9743092104886921,
      0.9828323010328516,
      0.9913958869243519,
      1.0
   };
   private static final int[] kSRGBSamples1 = new int[]{
      0,
      3,
      6,
      10,
      13,
      15,
      18,
      20,
      22,
      23,
      25,
      27,
      28,
      30,
      31,
      32,
      34,
      35,
      36,
      37,
      38,
      39,
      40,
      41,
      42,
      43,
      44,
      45,
      46,
      47,
      48,
      49,
      49,
      50,
      51,
      52,
      53,
      53,
      54,
      55,
      56,
      56,
      57,
      58,
      58,
      59,
      60,
      61,
      61,
      62,
      62,
      63,
      64,
      64,
      65,
      66,
      66,
      67,
      67,
      68,
      68,
      69,
      70,
      70,
      71,
      71,
      72,
      72,
      73,
      73,
      74,
      74,
      75,
      76,
      76,
      77,
      77,
      78,
      78,
      79,
      79,
      79,
      80,
      80,
      81,
      81,
      82,
      82,
      83,
      83,
      84,
      84,
      85,
      85,
      85,
      86,
      86,
      87,
      87,
      88,
      88,
      88,
      89,
      89,
      90,
      90,
      91,
      91,
      91,
      92,
      92,
      93,
      93,
      93,
      94,
      94,
      95,
      95,
      95,
      96,
      96,
      97,
      97,
      97,
      98,
      98,
      98,
      99,
      99,
      99,
      100,
      100,
      101,
      101,
      101,
      102,
      102,
      102,
      103,
      103,
      103,
      104,
      104,
      104,
      105,
      105,
      106,
      106,
      106,
      107,
      107,
      107,
      108,
      108,
      108,
      109,
      109,
      109,
      110,
      110,
      110,
      110,
      111,
      111,
      111,
      112,
      112,
      112,
      113,
      113,
      113,
      114,
      114,
      114,
      115,
      115,
      115,
      115,
      116,
      116,
      116,
      117,
      117,
      117,
      118,
      118,
      118,
      118,
      119,
      119,
      119,
      120
   };
   private static final int[] kSRGBSamples2 = new int[]{
      120,
      121,
      122,
      124,
      125,
      126,
      127,
      128,
      129,
      130,
      131,
      132,
      133,
      134,
      135,
      136,
      137,
      138,
      139,
      140,
      141,
      142,
      143,
      144,
      145,
      146,
      147,
      148,
      148,
      149,
      150,
      151,
      152,
      153,
      154,
      155,
      155,
      156,
      157,
      158,
      159,
      159,
      160,
      161,
      162,
      163,
      163,
      164,
      165,
      166,
      167,
      167,
      168,
      169,
      170,
      170,
      171,
      172,
      173,
      173,
      174,
      175,
      175,
      176,
      177,
      178,
      178,
      179,
      180,
      180,
      181,
      182,
      182,
      183,
      184,
      185,
      185,
      186,
      187,
      187,
      188,
      189,
      189,
      190,
      190,
      191,
      192,
      192,
      193,
      194,
      194,
      195,
      196,
      196,
      197,
      197,
      198,
      199,
      199,
      200,
      200,
      201,
      202,
      202,
      203,
      203,
      204,
      205,
      205,
      206,
      206,
      207,
      208,
      208,
      209,
      209,
      210,
      210,
      211,
      212,
      212,
      213,
      213,
      214,
      214,
      215,
      215,
      216,
      216,
      217,
      218,
      218,
      219,
      219,
      220,
      220,
      221,
      221,
      222,
      222,
      223,
      223,
      224,
      224,
      225,
      226,
      226,
      227,
      227,
      228,
      228,
      229,
      229,
      230,
      230,
      231,
      231,
      232,
      232,
      233,
      233,
      234,
      234,
      235,
      235,
      236,
      236,
      237,
      237,
      238,
      238,
      238,
      239,
      239,
      240,
      240,
      241,
      241,
      242,
      242,
      243,
      243,
      244,
      244,
      245,
      245,
      246,
      246,
      246,
      247,
      247,
      248,
      248,
      249,
      249,
      250,
      250,
      251,
      251,
      251,
      252,
      252,
      253,
      253,
      254,
      254,
      255,
      255
   };

   public BufferedImage read(byte[] jpegRawData) throws Exception {
      Info info = new Info();
      this.offset = 0;
      this.updateJpegInfo(jpegRawData, info);
      if (info.isLossless) {
         JpegLosslessDecoder decoder = new JpegLosslessDecoder();
         return decoder.read(jpegRawData);
      } else {
         return getBufferdImageFromInfo(info);
      }
   }

   public BufferedImage read(File file) throws Exception {
      byte[] bytes = new byte[(int)file.length()];

      try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
         dis.readFully(bytes);
      }

      return this.read(bytes);
   }

   public Rectangle readDimension(File file) throws Exception {
      byte[] bytes = new byte[(int)file.length()];

      try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
         dis.readFully(bytes);
      }

      return this.readDimension(bytes);
   }

   public Rectangle readDimension(byte[] jpegRawData) throws Exception {
      Info info = new Info();
      this.offset = 0;
      this.isDimensionOnly = true;
      this.updateJpegInfo(jpegRawData, info);
      this.isDimensionOnly = false;
      if (info.isLossless) {
         JpegLosslessDecoder decoder = new JpegLosslessDecoder();
         BufferedImage image = decoder.read(jpegRawData);
         return new Rectangle(image.getWidth(), image.getHeight());
      } else {
         return new Rectangle(info.width, info.height);
      }
   }

   private static BufferedImage getFromGray(int width, int height, byte[] input) {
      BufferedImage image = new BufferedImage(width, height, 10);
      byte[] pixelsByte = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
      System.arraycopy(input, 0, pixelsByte, 0, pixelsByte.length);
      return image;
   }

   private static void fromRGBToBGR(byte[] input) {
      int ii = input.length;

      for (int i = 0; i < ii; i += 3) {
         byte t = input[i];
         input[i] = input[i + 2];
         input[i + 2] = t;
      }
   }

   private static void fromYUVtoBGR(byte[] input) {
      int ii = input.length;

      for (int i = 0; i < ii; i += 3) {
         int y = ((input[i] & 255) << 8) + 128;
         int u = (input[i + 1] & 255) - 128;
         int v = (input[i + 2] & 255) - 128;
         int r = y + 359 * v >> 8;
         int g = y - 88 * u - 183 * v >> 8;
         int b = y + 454 * u >> 8;
         input[i] = b < 0 ? 0 : (b > 255 ? -1 : (byte)b);
         input[i + 1] = g < 0 ? 0 : (g > 255 ? -1 : (byte)g);
         input[i + 2] = r < 0 ? 0 : (r > 255 ? -1 : (byte)r);
      }
   }

   private static BufferedImage getFromCMYK(int width, int height, byte[] input) {
      BufferedImage image = new BufferedImage(width, height, 5);
      byte[] pixels = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
      int ii = input.length;

      for (int i = 0; i < ii; i++) {
         input[i] = (byte)(~(input[i] & 255));
      }

      EnumeratedSpace.convertCMYKToBGR(input, pixels);
      return image;
   }

   private static BufferedImage getFromYCCK(int width, int height, byte[] input) {
      BufferedImage image = new BufferedImage(width, height, 5);
      byte[] pixels = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
      int ii = input.length;

      for (int i = 0; i < ii; i++) {
         input[i] = (byte)(~(input[i] & 255));
      }

      int componentLength = width * height * 4;

      for (int i = 0; i < componentLength; i += 4) {
         int y = input[i] & 255;
         int u = input[i + 1] & 255;
         int v = input[i + 2] & 255;
         int c = (int)(434.456 - y - 1.402 * v);
         int m = (int)(119.541 - y + 0.344 * u + 0.714 * v);
         y = (int)(481.816 - y - 1.772 * u);
         input[i] = c < 0 ? 0 : (c > 255 ? -1 : (byte)c);
         input[i + 1] = m < 0 ? 0 : (m > 255 ? -1 : (byte)m);
         input[i + 2] = y < 0 ? 0 : (y > 255 ? -1 : (byte)y);
      }

      EnumeratedSpace.convertCMYKToBGR(input, pixels);
      return image;
   }

   private static BufferedImage getBufferdImageFromInfo(Info info) throws IOException {
      BufferedImage image = null;
      switch (info.nComp) {
         case 1: {
            byte[] input = decodeSamples(info);
            image = getFromGray(info.width, info.height, input);
            break;
         }
         case 2:
            throw new IOException("two color component jpegs not supported yet");
         case 3: {
            image = new BufferedImage(info.width, info.height, 5);
            byte[] input = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
            decodeSamplesWithOutput(info, input);
            if (info.adobe != null) {
               if (info.adobe.transformCode == 0) {
                  fromRGBToBGR(input);
               } else {
                  fromYUVtoAdobeRGBtoSRGBFast(input);
               }
            } else {
               fromYUVtoBGR(input);
            }
            break;
         }
         case 4: {
            byte[] input = decodeSamples(info);
            if (info.adobe == null) {
               image = getFromCMYK(info.width, info.height, input);
            } else if (info.adobe.transformCode == 0) {
               image = getFromCMYK(info.width, info.height, input);
            } else {
               image = getFromYCCK(info.width, info.height, input);
            }
         }
      }

      if (info.orientation != 1 && image != null) {
         image = doOrientation(image, info.orientation);
      }

      return image;
   }

   private static BufferedImage doOrientation(BufferedImage image, int orientation) {
      byte[] v = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
      int nc = image.getColorModel().getNumComponents();
      int h = image.getHeight();
      int w = image.getWidth();
      int ii = h / 2;
      int jj = w / 2;
      int ww = w * nc;
      byte[] aa = new byte[ww];
      byte[] bb = new byte[ww];
      byte[] t = new byte[nc];
      switch (orientation) {
         case 2:
            for (int i = 0; i < h; i++) {
               for (int j = 0; j < jj; j++) {
                  int aOffset = (i * w + j) * nc;
                  int bOffset = (i * w + w - j - 1) * nc;

                  for (int k = 0; k < nc; k++) {
                     t[k] = v[aOffset + k];
                     v[aOffset + k] = v[bOffset + k];
                     v[bOffset + k] = t[k];
                  }
               }
            }
            break;
         case 3:
            for (int i = 0; i < ii; i++) {
               int aOffset = i * ww;
               int bOffset = (h - i - 1) * ww;
               System.arraycopy(v, aOffset, aa, 0, ww);
               System.arraycopy(v, bOffset, bb, 0, ww);
               System.arraycopy(aa, 0, v, bOffset, ww);
               System.arraycopy(bb, 0, v, aOffset, ww);
            }

            for (int var28 = 0; var28 < h; var28++) {
               for (int j = 0; j < jj; j++) {
                  int aOffset = (var28 * w + j) * nc;
                  int bOffset = (var28 * w + w - j - 1) * nc;

                  for (int k = 0; k < nc; k++) {
                     t[k] = v[aOffset + k];
                     v[aOffset + k] = v[bOffset + k];
                     v[bOffset + k] = t[k];
                  }
               }
            }
            break;
         case 4:
            for (int i = 0; i < ii; i++) {
               int aOffset = i * ww;
               int bOffset = (h - i - 1) * ww;
               System.arraycopy(v, aOffset, aa, 0, ww);
               System.arraycopy(v, bOffset, bb, 0, ww);
               System.arraycopy(aa, 0, v, bOffset, ww);
               System.arraycopy(bb, 0, v, aOffset, ww);
            }
         case 5:
         case 7:
         default:
            break;
         case 6: {
            BufferedImage res = new BufferedImage(h, w, image.getType());
            PixGet pg = Access.getPixGet(image);

            for (int i = 0; i < w; i++) {
               for (int j = 0; j < h; j++) {
                  res.setRGB(h - 1 - j, i, pg.getRGB(i, j));
               }
            }

            return res;
         }
         case 8: {
            BufferedImage res8 = new BufferedImage(h, w, image.getType());
            PixGet pg8 = Access.getPixGet(image);

            for (int i = 0; i < w; i++) {
               for (int j = 0; j < h; j++) {
                  res8.setRGB(j, w - 1 - i, pg8.getRGB(i, j));
               }
            }

            return res8;
         }
      }

      return image;
   }

   public byte[] readComponentsAsRawBytes(byte[] jpegRawData) throws Exception {
      Info info = new Info();
      this.offset = 0;
      this.updateJpegInfo(jpegRawData, info);
      return decodeSamples(info);
   }

   public byte[] readAsUnconvertedBytes(byte[] jpegRawData, int adobeColorTransform, Info info) throws Exception {
      this.offset = 0;
      this.updateJpegInfo(jpegRawData, info);
      byte[] output = decodeSamples(info);
      switch (info.nComp) {
         case 3:
            int colorTransformx = adobeColorTransform == -1 ? 1 : adobeColorTransform;
            if (info.adobe != null) {
               if (info.adobe.transformCode != 0) {
                  convertYUVToRGB(output);
               }
            } else if (colorTransformx == 1) {
               convertYUVToRGB(output);
            }
            break;
         case 4:
            int colorTransform = adobeColorTransform == -1 ? 0 : adobeColorTransform;
            if (info.adobe != null) {
               if (info.adobe.transformCode != 0) {
                  convertYCCKtoCMYK(output);
               }
            } else if (colorTransform == 1) {
               convertYCCKtoCMYK(output);
            }
      }

      return output;
   }

   private static void convertYCCKtoCMYK(byte[] output) {
      int len = output.length;

      for (int i = 0; i < len; i += 4) {
         int y = output[i] & 255;
         int u = output[i + 1] & 255;
         int v = output[i + 2] & 255;
         double cc = 434.456 - y - 1.402 * v;
         double mm = 119.541 - y + 0.344 * u + 0.714 * v;
         double yy = 481.816 - y - 1.772 * u;
         output[i] = cc < 0.0 ? 0 : (cc > 255.0 ? -1 : (byte)cc);
         output[i + 1] = mm < 0.0 ? 0 : (mm > 255.0 ? -1 : (byte)mm);
         output[i + 2] = yy < 0.0 ? 0 : (yy > 255.0 ? -1 : (byte)yy);
      }
   }

   private static void convertYUVToRGB(byte[] output) {
      int len = output.length;

      for (int i = 0; i < len; i += 3) {
         int y = output[i] & 255;
         int u = (output[i + 1] & 255) - 128;
         int v = (output[i + 2] & 255) - 128;
         int r = y + 45 * v / 32;
         int g = y - (11 * u + 23 * v) / 32;
         int b = y + 113 * u / 64;
         output[i] = r < 0 ? 0 : (r > 255 ? -1 : (byte)r);
         output[i + 1] = g < 0 ? 0 : (g > 255 ? -1 : (byte)g);
         output[i + 2] = b < 0 ? 0 : (b > 255 ? -1 : (byte)b);
      }
   }

   private static byte[] decodeSamples(Info info) {
      byte[] output = new byte[info.width * info.height * info.nComp];
      decodeSamplesWithOutput(info, output);
      return output;
   }

   public static void decodeSamplesWithOutputBytes(Info info, byte[] output) {
      int nc = info.nComp;
      int iw = info.width;
      int ih = info.height;
      int[] wTable = new int[iw];

      for (int w = 0; w < iw; w++) {
         wTable[w] = (w & 268435448) << 3 | w & 7;
      }

      for (int i = 0; i < nc; i++) {
         Component comp = info.frame.components.get(i);
         int blockX18 = comp.blocksX + 1 << 3;
         float scaleX = comp.h * 1.0F / info.maxH;
         float scaleY = comp.v * 1.0F / info.maxV;
         byte[] compData = comp.codeBytes;
         int po = i;
         if (scaleX == 1.0F && scaleY == 1.0F) {
            for (int h = 0; h < ih; h++) {
               int yi = blockX18 * (h & 268435448) | (h & 7) << 3;

               for (int var20 = 0; var20 < iw; var20++) {
                  output[po] = compData[yi + wTable[var20]];
                  po += nc;
               }
            }
         } else {
            for (int h = 0; h < ih; h++) {
               int k = (int)(h * scaleY);
               int yi = blockX18 * (k & 268435448) | (k & 7) << 3;

               for (int var19 = 0; var19 < iw; var19++) {
                  int j = (int)(var19 * scaleX);
                  output[po] = compData[yi + wTable[j]];
                  po += nc;
               }
            }
         }
      }
   }

   private static void decodeSamplesWithOutput(Info info, byte[] output) {
      if (info.useBytes) {
         decodeSamplesWithOutputBytes(info, output);
      } else {
         int nc = info.nComp;
         int iw = info.width;
         int ih = info.height;
         int[] wTable = new int[iw];

         for (int w = 0; w < iw; w++) {
            wTable[w] = (w & 268435448) << 3 | w & 7;
         }

         for (int i = 0; i < nc; i++) {
            Component comp = info.frame.components.get(i);
            int blockX18 = comp.blocksX + 1 << 3;
            float scaleX = comp.h * 1.0F / info.maxH;
            float scaleY = comp.v * 1.0F / info.maxV;
            short[] compData = comp.codeBlock;
            int po = i;
            if (scaleX == 1.0F && scaleY == 1.0F) {
               for (int h = 0; h < ih; h++) {
                  int yi = blockX18 * (h & 268435448) | (h & 7) << 3;

                  for (int var20 = 0; var20 < iw; var20++) {
                     output[po] = (byte)compData[yi + wTable[var20]];
                     po += nc;
                  }
               }
            } else {
               for (int h = 0; h < ih; h++) {
                  int k = (int)(h * scaleY);
                  int yi = blockX18 * (k & 268435448) | (k & 7) << 3;

                  for (int var19 = 0; var19 < iw; var19++) {
                     int j = (int)(var19 * scaleX);
                     output[po] = (byte)compData[yi + wTable[j]];
                     po += nc;
                  }
               }
            }
         }
      }
   }

   private void updateJpegInfo(byte[] data, Info info) throws Exception {
      this.data = data;
      HashMap<Integer, int[]> qTables = new HashMap<>();
      int length = data.length;
      int ri = 0;
      Frame frame = new Frame();
      if (this.readUShort() != 65496) {
         throw new Exception("This File is not a valid JPEG");
      } else {
         HTree[] huffmanTablesAC = new HTree[10];
         HTree[] huffmanTablesDC = new HTree[10];
         int markerRead = this.readUShort();
         boolean hasSOF = false;
         boolean hasSOI = false;
         boolean canDecode = true;

         while (markerRead != 65497 && this.offset + 1 < length && canDecode) {
            switch (markerRead) {
               case 65472:
               case 65473:
               case 65474:
                  info.isProgressive = markerRead == 65474;
                  if (hasSOF && hasSOI) {
                     canDecode = false;
                  } else {
                     hasSOF = true;
                     this.updateSOFMarkers(frame, markerRead, data);
                     if (this.isDimensionOnly) {
                        info.width = frame.scanH;
                        info.height = frame.scanV;
                        return;
                     }
                  }
                  break;
               case 65475:
               case 65477:
               case 65478:
               case 65479:
                  info.isLossless = true;
                  return;
               case 65476:
                  this.updateDHTMarkers(data, huffmanTablesDC, huffmanTablesAC);
                  break;
               case 65480:
               case 65484:
               case 65485:
               case 65486:
               case 65487:
               case 65488:
               case 65489:
               case 65490:
               case 65491:
               case 65492:
               case 65493:
               case 65494:
               case 65495:
               case 65497:
               case 65500:
               case 65502:
               case 65503:
               case 65520:
               case 65521:
               case 65522:
               case 65523:
               case 65524:
               case 65525:
               case 65526:
               case 65527:
               case 65528:
               case 65529:
               case 65530:
               case 65531:
               case 65532:
               case 65533:
               default:
                  int len = this.readUShort();
                  this.offset += len - 2;
                  break;
               case 65481:
               case 65482:
               case 65483:
                  throw new Exception("Arithmetic encoded Jpeg is not supported yet");
               case 65496:
                  hasSOI = true;
                  break;
               case 65498:
                  info.useBytes = !info.isProgressive && setInfoAndCheck(frame, info, qTables);

                  try {
                     if (info.useBytes) {
                        if (frame.components.get(0).codeBytes == null) {
                           initializeComponentsBytes(frame);
                        }
                     } else if (frame.components.get(0).codeBlock == null) {
                        initializeComponents(frame);
                     }

                     this.updateSOSMarkers(data, frame, huffmanTablesDC, huffmanTablesAC, ri, info.useBytes);
                  } catch (ArrayIndexOutOfBoundsException var14) {
                     LogWriter.writeLog("Incomplete Jpeg Data " + var14.getMessage());
                  }

                  if (info.useBytes) {
                     return;
                  }
                  break;
               case 65499:
                  this.updateDQT(data, qTables);
                  break;
               case 65501:
                  this.offset += 2;
                  ri = this.readUShort();
                  break;
               case 65504:
               case 65505:
               case 65506:
               case 65507:
               case 65508:
               case 65509:
               case 65510:
               case 65511:
               case 65512:
               case 65513:
               case 65514:
               case 65515:
               case 65516:
               case 65517:
               case 65518:
               case 65519:
                  this.updateAPPMarkers(markerRead, info);
                  break;
               case 65534:
                  this.readDataArray();
                  break;
               case 65535:
                  if ((data[this.offset] & 255) != 255) {
                     this.offset--;
                  }
            }

            if (this.offset + 1 >= length) {
               break;
            }

            markerRead = this.readUShort();

            while (markerRead >> 8 < 255 && this.offset + 1 < length) {
               markerRead = this.readUShort();
            }

         }

         setInfo(frame, info, qTables);
      }
   }

   private static boolean setInfoAndCheck(Frame frame, Info info, HashMap<Integer, int[]> qTables) {
      info.width = frame.scanH;
      info.height = frame.scanV;

      for (Component component : frame.components) {
         component.qTable = qTables.get(component.qID);
         if (component.qTable == null) {
            return false;
         }
      }

      info.maxH = frame.maxH;
      info.maxV = frame.maxV;
      info.nComp = frame.components.size();
      info.frame = frame;
      return true;
   }

   private static void setInfo(Frame frame, Info info, HashMap<Integer, int[]> qTables) {
      info.width = frame.scanH;
      info.height = frame.scanV;

      for (Component component : frame.components) {
         component.qTable = qTables.get(component.qID);
         buildComponentData(component, frame.precision);
      }

      info.maxH = frame.maxH;
      info.maxV = frame.maxV;
      info.nComp = frame.components.size();
      info.frame = frame;
   }

   private void updateSOFMarkers(Frame frame, int markerRead, byte[] data1) {
      this.offset += 2;
      frame.extended = markerRead == 65473;
      frame.progressive = markerRead == 65474;
      frame.precision = data1[this.offset++] & 255;
      frame.scanV = this.readUShort();
      frame.scanH = this.readUShort();
      int componentsCount = data1[this.offset++] & 255;
      int maxH = 0;
      int maxV = 0;

      for (int i = 0; i < componentsCount; i++) {
         int componentId = data1[this.offset] & 255;
         int vh = data1[this.offset + 1] & 255;
         int h = vh >> 4;
         int v = vh & 15;
         maxH = Math.max(maxH, h);
         maxV = Math.max(maxV, v);
         int qId = data1[this.offset + 2] & 255;
         Component comp = new Component();
         comp.h = h;
         comp.v = v;
         comp.qID = qId;
         frame.components.add(comp);
         frame.componentID.put(componentId, frame.components.size() - 1);
         this.offset += 3;
      }

      frame.maxH = maxH;
      frame.maxV = maxV;
   }

   private void updateDHTMarkers(byte[] data1, HTree[] huffmanTablesDC, HTree[] huffmanTablesAC) {
      int huffmanLength = this.readUShort();
      int i = 2;

      while (i < huffmanLength) {
         int huffmanTableSpec = data1[this.offset++] & 255;
         int[] codeLengths = new int[16];
         int codeLengthTotal = 0;

         for (int j = 0; j < 16; j++) {
            codeLengths[j] = data1[this.offset] & 255;
            codeLengthTotal += codeLengths[j];
            this.offset++;
         }

         int[] huffmanValues = new int[codeLengthTotal];

         for (int j = 0; j < codeLengthTotal; this.offset++) {
            huffmanValues[j] = data1[this.offset] & 255;
            j++;
         }

         i += 17 + codeLengthTotal;
         if (huffmanTableSpec >> 4 == 0) {
            huffmanTablesDC[huffmanTableSpec & 15] = generateHuffmanTable(codeLengths, huffmanValues);
         } else {
            huffmanTablesAC[huffmanTableSpec & 15] = generateHuffmanTable(codeLengths, huffmanValues);
         }
      }
   }

   private void updateSOSMarkers(byte[] data1, Frame frame, HTree[] huffmanTablesDC, HTree[] huffmanTablesAC, int ri, boolean allAssigned) {
      this.offset += 2;
      int sc = data1[this.offset++] & 255;
      List<Component> components = new ArrayList<>();

      for (int i = 0; i < sc; i++) {
         int componentIndex = frame.componentID.get(data1[this.offset++] & 255);
         Component component = frame.components.get(componentIndex);
         int tableSpec = data1[this.offset++] & 255;
         component.huffmanTableDC = huffmanTablesDC[tableSpec >> 4];
         component.huffmanTableAC = huffmanTablesAC[tableSpec & 15];
         components.add(component);
      }

      int sStart = data1[this.offset++] & 255;
      int sEnd = data1[this.offset++] & 255;
      int sApprox = data1[this.offset++] & 255;
      if (allAssigned) {
         JpegScannerInts scanner = new JpegScannerInts(data1);
         this.offset = scanner.decodeScan(this.offset, frame, components, ri, sStart, sEnd, sApprox >> 4, sApprox & 15);
      } else {
         JpegScanner scanner = new JpegScanner(data1);
         this.offset = scanner.decodeScan(this.offset, frame, components, ri, sStart, sEnd, sApprox >> 4, sApprox & 15);
      }
   }

   private void updateAPPMarkers(int markerRead, Info info) {
      byte[] apps = this.readDataArray();
      if (apps.length != 0) {
         if (markerRead == 65504 && isJFIF(apps)) {
            JFIFHolder jfif = new JFIFHolder();
            jfif.majorNo = apps[5] & 255;
            jfif.minorNo = apps[6] & 255;
            jfif.xDensity = (apps[8] & 255) << 8 | apps[9] & 255;
            jfif.yDensity = (apps[10] & 255) << 8 | apps[11] & 255;
            jfif.thumbnailWidth = apps[12] & 255;
            jfif.thumbnailHeight = apps[13] & 255;
            info.jfif = jfif;
         } else if (markerRead == 65518 && isAdobe(apps)) {
            AdobeHolder adobe = new AdobeHolder();
            adobe.version = apps[6] & 255;
            adobe.flag0 = (apps[7] & 255) << 8 | apps[8] & 255;
            adobe.flag1 = (apps[9] & 255) << 8 | apps[10] & 255;
            adobe.transformCode = apps[11] & 255;
            info.adobe = adobe;
         } else if (markerRead == 65505 && apps[0] == 69 && apps[1] == 120 && apps[2] == 105 && apps[3] == 102) {
            try {
               info.orientation = parseExifOrientation(apps, 6);
            } catch (Exception var9) {
               LogWriter.writeLog("Error reading Exif data " + var9);
            }
         }
      }
   }

   private static int parseExifOrientation(byte[] apps, int startOffset) {
      if (apps.length < startOffset + 8) return 1;
      byte[] edata = new byte[apps.length - startOffset];
      System.arraycopy(apps, startOffset, edata, 0, edata.length);
      // Determine byte order
      boolean bigEndian;
      if (edata[0] == 'M' && edata[1] == 'M') {
         bigEndian = true;
      } else if (edata[0] == 'I' && edata[1] == 'I') {
         bigEndian = false;
      } else {
         return 1;
      }
      // Read IFD0 offset
      int ifdOffset = bigEndian
            ? (edata[4] & 0xFF) << 24 | (edata[5] & 0xFF) << 16 | (edata[6] & 0xFF) << 8 | (edata[7] & 0xFF)
            : (edata[7] & 0xFF) << 24 | (edata[6] & 0xFF) << 16 | (edata[5] & 0xFF) << 8 | (edata[4] & 0xFF);
      if (ifdOffset + 2 > edata.length) return 1;
      int entryCount = bigEndian
            ? (edata[ifdOffset] & 0xFF) << 8 | (edata[ifdOffset + 1] & 0xFF)
            : (edata[ifdOffset + 1] & 0xFF) << 8 | (edata[ifdOffset] & 0xFF);
      for (int i = 0; i < entryCount; i++) {
         int entryOffset = ifdOffset + 2 + i * 12;
         if (entryOffset + 12 > edata.length) break;
         int tag = bigEndian
               ? (edata[entryOffset] & 0xFF) << 8 | (edata[entryOffset + 1] & 0xFF)
               : (edata[entryOffset + 1] & 0xFF) << 8 | (edata[entryOffset] & 0xFF);
         if (tag == 0x0112) { // Orientation tag
            int value = bigEndian
                  ? (edata[entryOffset + 8] & 0xFF) << 8 | (edata[entryOffset + 9] & 0xFF)
                  : (edata[entryOffset + 9] & 0xFF) << 8 | (edata[entryOffset + 8] & 0xFF);
            return value >= 1 && value <= 8 ? value : 1;
         }
      }
      return 1;
   }

   private void updateDQT(byte[] data, HashMap<Integer, int[]> qTables) {
      int dqtLen = this.readUShort();
      int quantizationTablesEnd = dqtLen + this.offset - 2;

      while (this.offset < quantizationTablesEnd) {
         int qs = data[this.offset++] & 255;
         int[] tableData = new int[64];
         if (qs >> 4 == 0) {
            for (int i = 0; i < 64; i++) {
               int z = ZIGZAGORDER[i];
               tableData[z] = data[this.offset++] & 255;
            }
         } else if (qs >> 4 == 1) {
            for (int i = 0; i < 64; i++) {
               int z = ZIGZAGORDER[i];
               tableData[z] = this.readUShort();
            }
         }

         qTables.put(qs & 15, tableData);
      }
   }

   private int readUShort() {
      int value = (this.data[this.offset] & 255) << 8 | this.data[this.offset + 1] & 255;
      this.offset += 2;
      return value;
   }

   private static boolean isJFIF(byte[] db) {
      return (db[0] & 255) == 74 && (db[1] & 255) == 70 && (db[2] & 255) == 73 && (db[3] & 255) == 70 && (db[4] & 255) == 0;
   }

   private static boolean isAdobe(byte[] db) {
      return (db[0] & 255) == 65 && (db[1] & 255) == 100 && (db[2] & 255) == 111 && (db[3] & 255) == 98 && (db[4] & 255) == 101;
   }

   private byte[] readDataArray() {
      int len = this.readUShort();
      byte[] bb = new byte[len - 2];
      if (len + this.offset < this.data.length) {
         System.arraycopy(this.data, this.offset, bb, 0, bb.length);
         this.offset += bb.length;
      }

      return bb;
   }

   private static void initializeComponentsBytes(Frame frame) {
      int mcusPerLine = (int)Math.ceil(frame.scanH / 8.0 / frame.maxH);
      int mcusPerColumn = (int)Math.ceil(frame.scanV / 8.0 / frame.maxV);

      for (Component component : frame.components) {
         int blocksPerLine = (int)Math.ceil(Math.ceil(frame.scanH / 8.0) * component.h / (1.0 * frame.maxH));
         int blocksPerColumn = (int)Math.ceil(Math.ceil(frame.scanV / 8.0) * component.v / (1.0 * frame.maxV));
         int blocksPerLineForMcu = mcusPerLine * component.h;
         int blocksPerColumnForMcu = mcusPerColumn * component.v;
         int blocksBufferSize = 64 * (blocksPerColumnForMcu + 1) * (blocksPerLineForMcu + 1);
         component.codeBytes = new byte[blocksBufferSize];
         component.blocksX = blocksPerLine;
         component.blocksY = blocksPerColumn;
      }

      frame.mcusX = mcusPerLine;
      frame.mcusY = mcusPerColumn;
   }

   private static void initializeComponents(Frame frame) {
      int mcusPerLine = (int)Math.ceil(frame.scanH / 8.0 / frame.maxH);
      int mcusPerColumn = (int)Math.ceil(frame.scanV / 8.0 / frame.maxV);

      for (Component component : frame.components) {
         int blocksPerLine = (int)Math.ceil(Math.ceil(frame.scanH / 8.0) * component.h / (1.0 * frame.maxH));
         int blocksPerColumn = (int)Math.ceil(Math.ceil(frame.scanV / 8.0) * component.v / (1.0 * frame.maxV));
         int blocksPerLineForMcu = mcusPerLine * component.h;
         int blocksPerColumnForMcu = mcusPerColumn * component.v;
         int blocksBufferSize = 64 * (blocksPerColumnForMcu + 1) * (blocksPerLineForMcu + 1);
         component.codeBlock = new short[blocksBufferSize];
         component.blocksX = blocksPerLine;
         component.blocksY = blocksPerColumn;
      }

      frame.mcusX = mcusPerLine;
      frame.mcusY = mcusPerColumn;
   }

   private static void buildComponentData(Component component, int bitsCount) {
      int blocksPerLine = component.blocksX;
      int blocksPerColumn = component.blocksY;
      if (bitsCount == 12) {
         for (int blockRow = 0; blockRow < blocksPerColumn; blockRow++) {
            for (int blockCol = 0; blockCol < blocksPerLine; blockCol++) {
               int offset = JpegScanner.getCodeBlockOffset(component, blockRow, blockCol);
               DCT.IDCTQ12(component, offset);
            }
         }
      } else {
         int[] p = new int[64];

         for (int blockRow = 0; blockRow < blocksPerColumn; blockRow++) {
            for (int blockCol = 0; blockCol < blocksPerLine; blockCol++) {
               int offset = JpegScanner.getCodeBlockOffset(component, blockRow, blockCol);
               DCT.IDCTQ(component, offset, p);
            }
         }
      }
   }

   private static HTree generateHuffmanTable(int[] codeLengths, int[] symbols) {
      int k = 0;
      int length = 16;
      Deque<IndexMap> code = new ArrayDeque<>();

      while (length > 0 && codeLengths[length - 1] == 0) {
         length--;
      }

      IndexMap p = new IndexMap(new HTree());
      code.push(p);

      for (int i = 0; i < length; i++) {
         int cc = codeLengths[i];

         for (int j = 0; j < cc; j++) {
            p = code.pop();
            p.children.nodes[p.index] = new HTree(symbols[k]);

            while (p.index > 0) {
               p = code.pop();
            }

            p.index++;
            code.push(p);

            while (code.size() <= i) {
               IndexMap q = new IndexMap(new HTree());
               code.push(q);
               p.children.nodes[p.index] = q.children;
               p = q;
            }

            k++;
         }

         if (i + 1 < length) {
            IndexMap q = new IndexMap(new HTree());
            code.push(q);
            p.children.nodes[p.index] = q.children;
            p = q;
         }
      }

      return code.getLast().children;
   }

   private static void fromYUVtoAdobeRGBtoSRGBFast(byte[] input) {
      int ii = input.length;

      for (int i = 0; i < ii; i += 3) {
         int y = ((input[i] & 255) << 8) + 128;
         int u = (input[i + 1] & 255) - 128;
         int v = (input[i + 2] & 255) - 128;
         int r = y + 359 * v >> 8;
         int g = y - 88 * u - 183 * v >> 8;
         int b = y + 454 * u >> 8;
         b = b < 0 ? 0 : (b > 255 ? 255 : b);
         g = g < 0 ? 0 : (g > 255 ? 255 : g);
         r = r < 0 ? 0 : (r > 255 ? 255 : r);
         double linR = LINEARTABLE[r];
         double linG = LINEARTABLE[g];
         double linB = LINEARTABLE[b];
         double Xn = linR * 0.57667 + linG * 0.18556 + linB * 0.18823;
         double Yn = linR * 0.29734 + linG * 0.62736 + linB * 0.07529;
         double Zn = linR * 0.02703 + linG * 0.07069 + linB * 0.99134;
         linR = Xn * 3.2406255 + Yn * -1.537208 + Zn * -0.4986286;
         linG = Xn * -0.9689307 + Yn * 1.8757561 + Zn * 0.0415175;
         linB = Xn * 0.0557101 + Yn * -0.2040211 + Zn * 1.0569959;
         r = toLinearRGB(linR);
         g = toLinearRGB(linG);
         b = toLinearRGB(linB);
         input[i] = (byte)b;
         input[i + 1] = (byte)g;
         input[i + 2] = (byte)r;
      }
   }

   private static int toLinearRGB(double cc) {
      cc = cc < 0.0 ? 0.0 : (cc > 1.0 ? 1.0 : cc);
      int scale = (int)Math.max(cc * 1023.0, 0.0);
      return scale < 192 ? kSRGBSamples1[scale] : kSRGBSamples2[scale / 4 - 48];
   }
}
