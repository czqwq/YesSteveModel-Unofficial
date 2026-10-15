package rip.ysm.imagestream.jpeg.data;

import rip.ysm.imagestream.jpeg.JpegDecoder;
import rip.ysm.imagestream.internal.DCT;
import java.util.Arrays;

public class JpegScannerInts extends JpegScanner {
   private final int[] p64 = new int[64];

   public JpegScannerInts(byte[] data) {
      super(data);
   }

   @Override
   void decodeOrdering(Component component, int offsetD, int decodeFn) {
      Arrays.fill(component.codeInts, 0);
      switch (decodeFn) {
         case 0:
            int e = this.findHuffmanValue(component.huffmanTableDC);
            int diff = e == 0 ? 0 : this.getNextFull(e) << this.successive;
            component.codeInts[0] = component.pred += diff;
            break;
         case 1:
            component.codeInts[0] = component.codeInts[0] | this.readBit() << this.successive;
            break;
         case 2:
            if (this.eobrun > 0) {
               this.eobrun--;
            } else {
               this.handleACFirst(component);
            }
            break;
         case 3:
            this.handleACSuccessive(component);
            break;
         case 4:
            this.handleBaseline(component);
      }

      DCT.IDCTQInts(component, this.p64);

      for (int i = 0; i < 64; i++) {
         component.codeBytes[i + offsetD] = (byte)component.codeInts[i];
      }
   }

   private void handleACSuccessive(Component component) {
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
               if (component.codeInts[z] != 0) {
                  component.codeInts[z] = component.codeInts[z] + (this.readBit() << this.successive);
               } else if (--c == 0) {
                  this.stateAC = this.stateAC == 2 ? 3 : 0;
               }
               break;
            case 3:
               if (component.codeInts[z] != 0) {
                  component.codeInts[z] = component.codeInts[z] + (this.readBit() << this.successive);
               } else {
                  component.codeInts[z] = this.stateNextAC << this.successive;
                  this.stateAC = 0;
               }
               break;
            case 4:
               if (component.codeInts[z] != 0) {
                  component.codeInts[z] = component.codeInts[z] + (this.readBit() << this.successive);
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

   private void handleBaseline(Component component) {
      int a = 1;
      int e = this.findHuffmanValue(component.huffmanTableDC);
      int diff = e == 0 ? 0 : this.getNextFull(e);
      component.codeInts[0] = component.pred += diff;

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
            component.codeInts[z] = this.getNextFull(d);
            a++;
         }
      }
   }

   private void handleACFirst(Component component) {
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
            component.codeInts[z] = this.getNextFull(d) * (1 << this.successive);
            a++;
         }
      }
   }
}
