package rip.ysm.imagestream.webp.enc;

final class LFFilters {
   private LFFilters() {
   }

   static int vp8_filter_mask(int limit, int blimit, int p3, int p2, int p1, int p0, int q0, int q1, int q2, int q3) {
      boolean mask = false;
      mask |= Math.abs(p3 - p2) > limit;
      mask |= Math.abs(p2 - p1) > limit;
      mask |= Math.abs(p1 - p0) > limit;
      mask |= Math.abs(q1 - q0) > limit;
      mask |= Math.abs(q2 - q1) > limit;
      mask |= Math.abs(q3 - q2) > limit;
      mask |= filtermaskcore(p1, p0, q0, q1) > blimit;
      return mask ? 0 : -1;
   }

   static int vp8_hevmask(int thresh, int p1, int p0, int q0, int q1) {
      int hev = 0;
      hev |= Math.abs(p1 - p0) > thresh ? -1 : 0;
      return hev | (Math.abs(q1 - q0) > thresh ? -1 : 0);
   }

   private static short unsignedToSigned(short in) {
      return (short)(in - 128);
   }

   private static short signedToUnsigned(short in) {
      return (short)(in + 128);
   }

   static void vp8_filter(int mask, int hev, FullGetSetPointer ptr, int op1, int op0, int oq1) {
      short ps1 = unsignedToSigned(ptr.getRel(op1));
      short ps0 = unsignedToSigned(ptr.getRel(op0));
      short qs0 = unsignedToSigned(ptr.getRel(0));
      short qs1 = unsignedToSigned(ptr.getRel(oq1));
      int filter_value = CUtils.byteClamp((short)(ps1 - qs1));
      filter_value &= hev;
      int var15 = CUtils.byteClamp((short)(filter_value + 3 * (qs0 - ps0)));
      var15 &= mask;
      int Filter1 = CUtils.byteClamp((short)(var15 + 4));
      int Filter2 = CUtils.byteClamp((short)(var15 + 3));
      Filter1 >>= 3;
      Filter2 >>= 3;
      short u = CUtils.byteClamp((short)(qs0 - Filter1));
      ptr.setRel(0, signedToUnsigned(u));
      u = CUtils.byteClamp((short)(ps0 + Filter2));
      ptr.setRel(op0, signedToUnsigned(u));
      var15 = Filter1 + 1;
      var15 >>= 1;
      var15 &= ~hev;
      u = CUtils.byteClamp((short)(qs1 - var15));
      ptr.setRel(oq1, signedToUnsigned(u));
      u = CUtils.byteClamp((short)(ps1 + var15));
      ptr.setRel(op1, signedToUnsigned(u));
   }

   static void loop_filter_horizontal_edge(FullGetSetPointer s, int p, GetPointer blimit, GetPointer limit, GetPointer thresh, int count) {
      int i = 0;

      do {
         filterCore(s, blimit, limit, thresh, p);
         s.inc();
      } while (++i < count * 8);
   }

   private static int getMask(GetPointer s, GetPointer blimit, GetPointer limit, int p) {
      return vp8_filter_mask(
         limit.get(),
         blimit.get(),
         s.getRel(-4 * p),
         s.getRel(-3 * p),
         s.getRel(-2 * p),
         s.getRel(-1 * p),
         s.getRel(0),
         s.getRel(p),
         s.getRel(2 * p),
         s.getRel(3 * p)
      );
   }

   private static int getHEV(FullGetSetPointer s, GetPointer thresh, int p) {
      return vp8_hevmask(thresh.get(), s.getRel(-2 * p), s.getRel(-1 * p), s.getRel(0), s.getRel(p));
   }

   private static void filterCore(FullGetSetPointer s, GetPointer blimit, GetPointer limit, GetPointer thresh, int p) {
      int mask = getMask(s, blimit, limit, p);
      int hev = getHEV(s, thresh, p);
      vp8_filter(mask, hev, s, -(p << 1), -p, p);
   }

   static void loop_filter_vertical_edge(FullGetSetPointer s, int p, GetPointer blimit, GetPointer limit, GetPointer thresh, int count) {
      int i = 0;

      do {
         filterCore(s, blimit, limit, thresh, 1);
         s.incBy(p);
      } while (++i < count * 8);
   }

   static void vp8_mbfilter(int mask, int hev, FullGetSetPointer ptr, int op2, int op1, int op0, int oq1, int oq2) {
      short ps2 = unsignedToSigned(ptr.getRel(op2));
      short ps1 = unsignedToSigned(ptr.getRel(op1));
      short ps0 = unsignedToSigned(ptr.getRel(op0));
      short qs0 = unsignedToSigned(ptr.getRel(0));
      short qs1 = unsignedToSigned(ptr.getRel(oq1));
      short qs2 = unsignedToSigned(ptr.getRel(oq2));
      short filter_value = CUtils.byteClamp((short)(ps1 - qs1));
      filter_value = CUtils.byteClamp((short)(filter_value + 3 * (qs0 - ps0)));
      filter_value = (short)(filter_value & mask);
      short Filter2 = (short)(filter_value & hev);
      short Filter1 = CUtils.byteClamp((short)(Filter2 + 4));
      Filter2 = CUtils.byteClamp((short)(Filter2 + 3));
      Filter1 = (short)(Filter1 >> 3);
      Filter2 = (short)(Filter2 >> 3);
      qs0 = CUtils.byteClamp((short)(qs0 - Filter1));
      ps0 = CUtils.byteClamp((short)(ps0 + Filter2));
      filter_value = (short)(filter_value & ~hev);
      short u = CUtils.byteClamp((short)(63 + filter_value * 27 >> 7));
      short s = CUtils.byteClamp((short)(qs0 - u));
      ptr.setRel(0, signedToUnsigned(s));
      s = CUtils.byteClamp((short)(ps0 + u));
      ptr.setRel(op0, signedToUnsigned(s));
      u = CUtils.byteClamp((short)(63 + filter_value * 18 >> 7));
      s = CUtils.byteClamp((short)(qs1 - u));
      ptr.setRel(oq1, signedToUnsigned(s));
      s = CUtils.byteClamp((short)(ps1 + u));
      ptr.setRel(op1, signedToUnsigned(s));
      u = CUtils.byteClamp((short)(63 + filter_value * 9 >> 7));
      s = CUtils.byteClamp((short)(qs2 - u));
      ptr.setRel(oq2, signedToUnsigned(s));
      s = CUtils.byteClamp((short)(ps2 + u));
      ptr.setRel(op2, signedToUnsigned(s));
   }

   static void mbloop_filter_horizontal_edge(FullGetSetPointer s, int p, GetPointer blimit, GetPointer limit, GetPointer thresh, int count) {
      int i = 0;
      s = s.shallowCopy();

      do {
         mbfiltercore(s, blimit, limit, thresh, p);
         s.inc();
      } while (++i < count * 8);
   }

   private static void mbfiltercore(FullGetSetPointer s, GetPointer blimit, GetPointer limit, GetPointer thresh, int p) {
      int mask = getMask(s, blimit, limit, p);
      int hev = getHEV(s, thresh, p);
      vp8_mbfilter(mask, hev, s, -3 * p, -2 * p, -p, p, 2 * p);
   }

   static void mbloop_filter_vertical_edge(FullGetSetPointer s, int p, GetPointer blimit, GetPointer limit, GetPointer thresh, int count) {
      int i = 0;
      s = s.shallowCopy();

      do {
         mbfiltercore(s, blimit, limit, thresh, 1);
         s.incBy(p);
      } while (++i < count * 8);
   }

   static int vp8_simple_filter_mask(int blimit, int p1, int p0, int q0, int q1) {
      return filtermaskcore(p1, p0, q0, q1) <= blimit ? -1 : 0;
   }

   private static int filtermaskcore(int p1, int p0, int q0, int q1) {
      return Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2;
   }

   static void vp8_simple_filter(int mask, FullGetSetPointer ptr, int op1, int op0, int oq1) {
      short p1 = unsignedToSigned(ptr.getRel(op1));
      short p0 = unsignedToSigned(ptr.getRel(op0));
      short q0 = unsignedToSigned(ptr.getRel(0));
      short q1 = unsignedToSigned(ptr.getRel(oq1));
      int filter_value = CUtils.byteClamp((short)(p1 - q1));
      int var13 = CUtils.byteClamp((short)(filter_value + 3 * (q0 - p0)));
      var13 &= mask;
      int Filter1 = CUtils.byteClamp((short)(var13 + 4));
      Filter1 >>= 3;
      short u = CUtils.byteClamp((short)(q0 - Filter1));
      ptr.setRel(0, signedToUnsigned(u));
      int Filter2 = CUtils.byteClamp((short)(var13 + 3));
      Filter2 >>= 3;
      u = CUtils.byteClamp((short)(p0 + Filter2));
      ptr.setRel(op0, signedToUnsigned(u));
   }

   static void vp8_loop_filter_simple_horizontal_edge(FullGetSetPointer y_ptr, int y_stride, GetPointer blimit) {
      int i = 0;
      y_ptr.savePos();

      do {
         int mask = vp8_simple_filter_mask(blimit.get(), y_ptr.getRel(-2 * y_stride), y_ptr.getRel(-1 * y_stride), y_ptr.getRel(0), y_ptr.getRel(y_stride));
         vp8_simple_filter(mask, y_ptr, -2 * y_stride, -y_stride, y_stride);
         y_ptr.inc();
      } while (++i < 16);

      y_ptr.rewindToSaved();
   }

   static void vp8_loop_filter_simple_vertical_edge(FullGetSetPointer y_ptr, int y_stride, GetPointer blimit) {
      int i = 0;
      y_ptr.savePos();

      do {
         int mask = vp8_simple_filter_mask(blimit.get(), y_ptr.getRel(-2), y_ptr.getRel(-1), y_ptr.get(), y_ptr.getRel(1));
         vp8_simple_filter(mask, y_ptr, -2, -1, 1);
         y_ptr.incBy(y_stride);
      } while (++i < 16);

      y_ptr.rewindToSaved();
   }

   static void vp8_loop_filter_mbh(FullGetSetPointer y_ptr, FullGetSetPointer u_ptr, FullGetSetPointer v_ptr, int y_stride, int uv_stride, LoopFilterInfo lfi) {
      mbloop_filter_horizontal_edge(y_ptr, y_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 2);
      if (u_ptr != null) {
         mbloop_filter_horizontal_edge(u_ptr, uv_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 1);
      }

      if (v_ptr != null) {
         mbloop_filter_horizontal_edge(v_ptr, uv_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 1);
      }
   }

   static void vp8_loop_filter_mbv(FullGetSetPointer y_ptr, FullGetSetPointer u_ptr, FullGetSetPointer v_ptr, int y_stride, int uv_stride, LoopFilterInfo lfi) {
      mbloop_filter_vertical_edge(y_ptr, y_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 2);
      if (u_ptr != null) {
         mbloop_filter_vertical_edge(u_ptr, uv_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 1);
      }

      if (v_ptr != null) {
         mbloop_filter_vertical_edge(v_ptr, uv_stride, lfi.mblim, lfi.lim, lfi.hev_thr, 1);
      }
   }

   static void vp8_loop_filter_bh(FullGetSetPointer y_ptr, FullGetSetPointer u_ptr, FullGetSetPointer v_ptr, int y_stride, int uv_stride, LoopFilterInfo lfi) {
      loop_filter_horizontal_edge(y_ptr.shallowCopyWithPosInc(y_stride << 2), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      loop_filter_horizontal_edge(y_ptr.shallowCopyWithPosInc(y_stride << 3), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      loop_filter_horizontal_edge(y_ptr.shallowCopyWithPosInc(12 * y_stride), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      if (u_ptr != null) {
         loop_filter_horizontal_edge(u_ptr.shallowCopyWithPosInc(uv_stride << 2), uv_stride, lfi.blim, lfi.lim, lfi.hev_thr, 1);
      }

      if (v_ptr != null) {
         loop_filter_horizontal_edge(v_ptr.shallowCopyWithPosInc(uv_stride << 2), uv_stride, lfi.blim, lfi.lim, lfi.hev_thr, 1);
      }
   }

   static void vp8_loop_filter_bhs(FullGetSetPointer y_ptr, int y_stride, GetPointer blimit) {
      vp8_loop_filter_simple_horizontal_edge(y_ptr.shallowCopyWithPosInc(y_stride << 2), y_stride, blimit);
      vp8_loop_filter_simple_horizontal_edge(y_ptr.shallowCopyWithPosInc(y_stride << 3), y_stride, blimit);
      vp8_loop_filter_simple_horizontal_edge(y_ptr.shallowCopyWithPosInc(12 * y_stride), y_stride, blimit);
   }

   static void vp8_loop_filter_bv(FullGetSetPointer y_ptr, FullGetSetPointer u_ptr, FullGetSetPointer v_ptr, int y_stride, int uv_stride, LoopFilterInfo lfi) {
      loop_filter_vertical_edge(y_ptr.shallowCopyWithPosInc(4), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      loop_filter_vertical_edge(y_ptr.shallowCopyWithPosInc(8), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      loop_filter_vertical_edge(y_ptr.shallowCopyWithPosInc(12), y_stride, lfi.blim, lfi.lim, lfi.hev_thr, 2);
      if (u_ptr != null) {
         loop_filter_vertical_edge(u_ptr.shallowCopyWithPosInc(4), uv_stride, lfi.blim, lfi.lim, lfi.hev_thr, 1);
      }

      if (v_ptr != null) {
         loop_filter_vertical_edge(v_ptr.shallowCopyWithPosInc(4), uv_stride, lfi.blim, lfi.lim, lfi.hev_thr, 1);
      }
   }

   static void vp8_loop_filter_bvs(FullGetSetPointer y_ptr, int y_stride, GetPointer blimit) {
      vp8_loop_filter_simple_vertical_edge(y_ptr.shallowCopyWithPosInc(4), y_stride, blimit);
      vp8_loop_filter_simple_vertical_edge(y_ptr.shallowCopyWithPosInc(8), y_stride, blimit);
      vp8_loop_filter_simple_vertical_edge(y_ptr.shallowCopyWithPosInc(12), y_stride, blimit);
   }
}
