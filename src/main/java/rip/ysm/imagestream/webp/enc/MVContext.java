package rip.ysm.imagestream.webp.enc;

class MVContext {
   static final MVContext[] vp8_default_mv_context = new MVContext[]{
      new MVContext(new short[]{162, 128, 225, 146, 172, 147, 214, 39, 156, 128, 129, 132, 75, 145, 178, 206, 239, 254, 254}),
      new MVContext(new short[]{164, 128, 204, 170, 119, 235, 140, 230, 228, 128, 130, 130, 74, 148, 180, 203, 236, 254, 254})
   };
   final FullGetSetPointer prob = new FullGetSetPointer(19);

   MVContext(short[] data) {
      this.prob.memcopyin(0, data, 0, this.prob.size());
   }

   MVContext(MVContext other) {
      this.prob.memcopyin(0, other.prob, 0, this.prob.size());
   }
}
