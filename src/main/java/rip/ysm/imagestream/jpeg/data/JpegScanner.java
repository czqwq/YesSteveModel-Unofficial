package rip.ysm.imagestream.jpeg.data;

import rip.ysm.imagestream.jpeg.JpegDecoder;
import java.util.List;

public class JpegScanner {
   static final int DCFIRST = 0;
   static final int DCSUCCESSIVE = 1;
   static final int ACFIRST = 2;
   static final int ACSUCCESSIVE = 3;
   static final int BASELINE = 4;
   private final byte[] data;
   int bitPos;
   int bitBuffer;
   int eobrun;
   int mcusX;
   int offset;
   int successive;
   int sStart;
   int sEnd;
   int stateAC;
   int stateNextAC;

   public JpegScanner(byte[] data) {
      this.data = data;
   }

   public int decodeScan(int off, Frame frame, List<Component> components, int resetInterval, int sStart, int sEnd, int sPrev, int successive) {
      this.mcusX = frame.mcusX;
      this.offset = off;
      this.successive = successive;
      this.sStart = sStart;
      this.sEnd = sEnd;
      int componentsLength = components.size();
      int decodeFn = getDecodeFn(frame, sStart, sPrev);
      int mcu = 0;
      int mcuExpected;
      if (componentsLength == 1) {
         mcuExpected = components.get(0).blocksX * components.get(0).blocksY;
      } else {
         mcuExpected = this.mcusX * frame.mcusY;
      }

      while (mcu < mcuExpected) {
         int nc = resetInterval != 0 ? Math.min(mcuExpected - mcu, resetInterval) : mcuExpected;

         for (int i = 0; i < componentsLength; i++) {
            components.get(i).pred = 0;
         }

         this.eobrun = 0;
         if (componentsLength == 1) {
            mcu = this.setSingleComponent(components, decodeFn, mcu, nc);
         } else {
            mcu = this.setMultiComponent(components, componentsLength, decodeFn, mcu, nc);
         }

         if (this.offset + 2 >= this.data.length) {
            break;
         }

         this.bitPos = 0;
         int[] markAndOff = new int[]{0, this.offset};
         findNextFileMarker(this.data, markAndOff);
         int marker = markAndOff[0];
         this.offset = markAndOff[1];
         if (marker <= 65280) {
            return this.offset;
         }

         if (marker < 65488 || marker > 65495) {
            break;
         }

         this.offset += 2;
      }

      return this.offset;
   }

   private static void findNextFileMarker(byte[] data, int[] markAndOff) {
      int pos = markAndOff[1];
      int currentMarker = (data[pos] & 255) << 8 | data[pos + 1] & 255;
      if (currentMarker >= 65472 && currentMarker <= 65534) {
         markAndOff[0] = currentMarker;
      } else {
         int newMarker;
         for (newMarker = (data[pos] & 255) << 8 | data[pos + 1] & 255;
            newMarker < 65472 || newMarker > 65534;
            newMarker = (data[pos] & 255) << 8 | data[pos + 1] & 255
         ) {
            if (++pos >= data.length - 1) {
               markAndOff[1] = pos;
               return;
            }
         }

         markAndOff[0] = newMarker;
         markAndOff[1] = pos;
      }
   }

   private static int getDecodeFn(Frame frame, int sStart, int sPrev) {
      int decodeFn;
      if (frame.progressive) {
         if (sStart == 0) {
            decodeFn = sPrev == 0 ? 0 : 1;
         } else {
            decodeFn = sPrev == 0 ? 2 : 3;
         }
      } else {
         decodeFn = 4;
      }

      return decodeFn;
   }

   private int setSingleComponent(List<Component> components, int decodeFn, int mcu, int nc) {
      Component component = components.get(0);

      for (int n = 0; n < nc; n++) {
         this.decodeBlock(component, decodeFn, mcu);
         mcu++;
      }

      return mcu;
   }

   private int setMultiComponent(List<Component> components, int componentsLength, int decodeFn, int mcu, int nc) {
      for (int n = 0; n < nc; n++) {
         for (int i = 0; i < componentsLength; i++) {
            Component component = components.get(i);
            int h = component.h;
            int v = component.v;

            for (int j = 0; j < v; j++) {
               for (int k = 0; k < h; k++) {
                  this.decodeMcu(component, decodeFn, mcu, j, k);
               }
            }
         }

         mcu++;
      }

      return mcu;
   }

   int findHuffmanValue(HTree tree) {
      HTree t = tree;

      do {
         t = t.nodes[this.readBit()];
      } while (!t.isEnd);

      return t.value;
   }

   int readBit() {
      if (this.bitPos > 0) {
         this.bitPos--;
         return this.bitBuffer >> this.bitPos & 1;
      } else {
         this.bitBuffer = this.data[this.offset++] & 255;
         this.offset = this.offset + (this.bitBuffer + 1 >> 8);
         this.bitPos = 7;
         return this.bitBuffer >> 7;
      }
   }

   int getNext(int length) {
      int n = 0;

      while (length > 0) {
         n = n << 1 | this.readBit();
         length--;
      }

      return n;
   }

   int getNextFull(int length) {
      if (length == 1) {
         return this.readBit() == 1 ? 1 : -1;
      } else {
         int n = this.getNext(length);
         return n >= 1 << length - 1 ? n : n + (-1 << length) + 1;
      }
   }

   private void decodeMcu(Component component, int decodeFn, int mcu, int row, int col) {
      int mcuRow = mcu / this.mcusX;
      int mcuCol = mcu % this.mcusX;
      int blockRow = mcuRow * component.v + row;
      int blockCol = mcuCol * component.h + col;
      int offsetD = getCodeBlockOffset(component, blockRow, blockCol);
      this.decodeOrdering(component, offsetD, decodeFn);
   }

   private void decodeBlock(Component component, int decodeFn, int mcu) {
      int blockRow = mcu / component.blocksX;
      int blockCol = mcu % component.blocksX;
      int offsetD = getCodeBlockOffset(component, blockRow, blockCol);
      this.decodeOrdering(component, offsetD, decodeFn);
   }

   void decodeOrdering(Component component, int offset, int decodeFn) {
      switch (decodeFn) {
         case 0:
            int e = this.findHuffmanValue(component.huffmanTableDC);
            int diff = e == 0 ? 0 : this.getNextFull(e) << this.successive;
            component.pred += diff;
            component.codeBlock[offset] = (short)component.pred;
            break;
         case 1:
            component.codeBlock[offset] = (short)(component.codeBlock[offset] | this.readBit() << this.successive);
            break;
         case 2:
            if (this.eobrun > 0) {
               this.eobrun--;
            } else {
               this.handleACFirst(component, offset);
            }
            break;
         case 3:
            this.handleACSuccessive(component, offset);
            break;
         case 4:
            this.handleBaseline(component, offset);
      }
   }

   private void handleACSuccessive(Component component, int offset1) {
      int a = this.sStart;
      int b = this.sEnd;
      int c = 0;

      while (a <= b) {
         int z = JpegDecoder.ZIGZAGORDER[a];
         switch (this.stateAC) {
            case 0:
               int f = this.findHuffmanValue(component.huffmanTableAC);
               int d = f & 15;
               c = f >> 4;
               if (d == 0) {
                  if (c < 15) {
                     this.eobrun = this.getNext(c) + (1 << c);
                     this.stateAC = 4;
                  } else {
                     c = 16;
                     this.stateAC = 1;
                  }
               } else {
                  this.stateNextAC = this.getNextFull(d);
                  this.stateAC = c != 0 ? 2 : 3;
               }
               continue;
            case 1:
            case 2:
               if (component.codeBlock[offset1 + z] != 0) {
                  component.codeBlock[offset1 + z] = (short)(component.codeBlock[offset1 + z] + (this.readBit() << this.successive));
               } else if (--c == 0) {
                  this.stateAC = this.stateAC == 2 ? 3 : 0;
               }
               break;
            case 3:
               if (component.codeBlock[offset1 + z] != 0) {
                  component.codeBlock[offset1 + z] = (short)(component.codeBlock[offset1 + z] + (this.readBit() << this.successive));
               } else {
                  component.codeBlock[offset1 + z] = (short)(this.stateNextAC << this.successive);
                  this.stateAC = 0;
               }
               break;
            case 4:
               if (component.codeBlock[offset1 + z] != 0) {
                  component.codeBlock[offset1 + z] = (short)(component.codeBlock[offset1 + z] + (this.readBit() << this.successive));
               }
         }

         a++;
      }

      if (this.stateAC == 4) {
         this.eobrun--;
         if (this.eobrun == 0) {
            this.stateAC = 0;
         }
      }
   }

   private void handleBaseline(Component component, int offset1) {
      int a = 1;
      int e = this.findHuffmanValue(component.huffmanTableDC);
      int diff = e == 0 ? 0 : this.getNextFull(e);
      component.pred += diff;
      component.codeBlock[offset1] = (short)component.pred;

      while (a < 64) {
         int f = this.findHuffmanValue(component.huffmanTableAC);
         int d = f & 15;
         int c = f >> 4;
         if (d == 0) {
            if (c < 15) {
               break;
            }

            a += 16;
         } else {
            a += c;
            int z = JpegDecoder.ZIGZAGORDER[a];
            component.codeBlock[offset1 + z] = (short)this.getNextFull(d);
            a++;
         }
      }
   }

   private void handleACFirst(Component component, int offset1) {
      int a = this.sStart;
      int b = this.sEnd;

      while (a <= b) {
         int f = this.findHuffmanValue(component.huffmanTableAC);
         int d = f & 15;
         int c = f >> 4;
         if (d == 0) {
            if (c < 15) {
               this.eobrun = this.getNext(c) + (1 << c) - 1;
               break;
            }

            a += 16;
         } else {
            a += c;
            int z = JpegDecoder.ZIGZAGORDER[a];
            component.codeBlock[offset1 + z] = (short)(this.getNextFull(d) * (1 << this.successive));
            a++;
         }
      }
   }

   public static int getCodeBlockOffset(Component component, int row, int col) {
      return 64 * ((component.blocksX + 1) * row + col);
   }
}
