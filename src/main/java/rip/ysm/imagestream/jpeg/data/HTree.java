package rip.ysm.imagestream.jpeg.data;

public class HTree {
   public final HTree[] nodes = new HTree[2];
   public boolean isEnd;
   public int value;

   public HTree() {
   }

   public HTree(int value) {
      this.value = value;
      this.isEnd = true;
   }
}
