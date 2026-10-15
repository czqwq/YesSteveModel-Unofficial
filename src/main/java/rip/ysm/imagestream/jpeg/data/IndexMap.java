package rip.ysm.imagestream.jpeg.data;

public class IndexMap {
   public int index;
   public final HTree children;

   public IndexMap(HTree children) {
      this.children = children;
   }
}
