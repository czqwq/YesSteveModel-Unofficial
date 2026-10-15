package rip.ysm.imagestream.webp.enc;

class VarianceFNs {
   SDF sdf;
   VF vf;
   SVF svf;
   SDXF sdx3f;
   SDXF sdx8f;
   SDXF sdx4df;
   COPY copymem;

   VarianceFNs copy() {
      VarianceFNs n = new VarianceFNs();
      n.sdf = this.sdf;
      n.vf = this.vf;
      n.svf = this.svf;
      n.sdx3f = this.sdx3f;
      n.sdx8f = this.sdx8f;
      n.sdx4df = this.sdx4df;
      n.copymem = this.copymem;
      return n;
   }

   interface COPY {
      void call(GetSetPointer var1, int var2, FullGetSetPointer var3, int var4, int var5);
   }

   interface SDF {
      long call(GetSetPointer var1, int var2, GetSetPointer var3, int var4);
   }

   interface SDXF {
      void call(GetSetPointer var1, int var2, GetSetPointer var3, int var4, int[] var5);
   }

   interface SVF {
      void call(GetSetPointer var1, int var2, int var3, int var4, GetSetPointer var5, int var6, VarianceResults var7);
   }

   interface VF {
      void call(GetSetPointer var1, int var2, GetSetPointer var3, int var4, VarianceResults var5);
   }
}
