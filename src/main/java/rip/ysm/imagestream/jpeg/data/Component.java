package rip.ysm.imagestream.jpeg.data;

public class Component {
   public int blocksX;
   public int blocksY;
   public short[] codeBlock;
   public final int[] codeInts = new int[64];
   public byte[] codeBytes;
   public int[] qTable;
   public int qID;
   public int pred;
   public int v;
   public int h;
   public HTree huffmanTableAC;
   public HTree huffmanTableDC;
   public int lsAC;
   public int lsDC;
   public int lsComp;
}
