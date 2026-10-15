package rip.ysm.imagestream.webp.enc;

final class Tokenize {
   private Tokenize() {
   }

   private static TokenExtra configureToken(FullGenArrPointer<TokenExtra> tp, TokenAlphabet tok, Compressor cpi, PlaneType type, int band, int pt, boolean skip) {
      int typ = type.ordinal();
      TokenExtra t = tp.get();
      if (t == null) {
         t = new TokenExtra();
         tp.setAndInc(t);
      } else {
         tp.inc();
      }

      t.Token = tok;
      t.context_tree = cpi.common.fc.coef_probs[typ][band][pt];
      t.skip_eob_node = skip;
      cpi.mb.coef_counts[typ][band][pt][tok.ordinal()]++;
      return t;
   }

   private static void configureAsEob(FullGenArrPointer<TokenExtra> tp, Compressor cpi, PlaneType type, int band, int pt) {
      configureToken(tp, TokenAlphabet.DCT_EOB_TOKEN, cpi, type, band, pt, false);
   }

   private static int configureAsGeneric(FullGenArrPointer<TokenExtra> tp, Compressor cpi, int v, PlaneType type, int band, int pt, boolean skip) {
      TokenValue tv = DCTValueConstants.getTokenValue(v);
      TokenAlphabet token = tv.token;
      TokenExtra t = configureToken(tp, token, cpi, type, band, pt, skip);
      t.Extra = tv.extra;
      return token.prevTokenClass;
   }

   static void tokenize2nd_order_b(Macroblock x, FullGenArrPointer<TokenExtra> tp, Compressor cpi) {
      MacroblockD xd = x.e_mbd;
      singleBlockfinalizeTokenize(PlaneType.Y2, tp, cpi, 24, xd.above_context.get().panes, xd.left_context.panes);
   }

   static void tokenize1st_order_b(Macroblock x, FullGenArrPointer<TokenExtra> tp, PlaneType type, Compressor cpi) {
      MacroblockD xd = x.e_mbd;
      FullGetSetPointer a = xd.above_context.get().panes;
      FullGetSetPointer l = xd.left_context.panes;

      for (int block = 0; block < 16; block++) {
         singleBlockfinalizeTokenize(type, tp, cpi, block, a, l);
      }

      for (int block = 16; block < 24; block++) {
         singleBlockfinalizeTokenize(PlaneType.UV, tp, cpi, block, a, l);
      }
   }

   private static void singleBlockfinalizeTokenize(
      PlaneType type, FullGenArrPointer<TokenExtra> tp, Compressor cpi, int block, FullGetSetPointer a, FullGetSetPointer l
   ) {
      BlockD bd = cpi.mb.e_mbd.block.getRel(block);
      int eob = bd.eob.get();
      FullGetSetPointer qcoeff_ptr = bd.qcoeff;
      int aPos = BlockD.vp8_block2above[block];
      int lPos = BlockD.vp8_block2left[block];
      int pt = a.getRel(aPos) + l.getRel(lPos);
      int c = type.start_coeff;
      if (c >= eob) {
         configureAsEob(tp, cpi, type, c, pt);
         a.setRel(aPos, l.setRel(lPos, (short)0));
      } else {
         int v = qcoeff_ptr.getRel(c);
         pt = configureAsGeneric(tp, cpi, v, type, c, pt, false);
         c++;

         while (c < eob) {
            int rc = W.zigzag[c];
            int var17 = qcoeff_ptr.getRel(rc);
            pt = configureAsGeneric(tp, cpi, var17, type, W.vp8CoefBands[c], pt, pt == 0);
            c++;
         }

         if (c < 16) {
            configureAsEob(tp, cpi, type, W.vp8CoefBands[c], pt);
         }

         a.setRel(aPos, l.setRel(lPos, (short)1));
      }
   }

   private static void stuffOrderHelper(FullGenArrPointer<TokenExtra> tp, FullGetSetPointer a, FullGetSetPointer l, Compressor cpi, PlaneType plane, int band) {
      int pt = a.get() + l.get();
      configureAsEob(tp, cpi, plane, band, pt);
      a.set(l.set((short)0));
   }

   static void stuff2nd_order_b(FullGenArrPointer<TokenExtra> tp, FullGetSetPointer a, FullGetSetPointer l, Compressor cpi) {
      stuffOrderHelper(tp, a, l, cpi, PlaneType.Y2, 0);
   }

   static void stuff1st_order_b(FullGenArrPointer<TokenExtra> tp, FullGetSetPointer a, FullGetSetPointer l, PlaneType type, Compressor cpi) {
      stuffOrderHelper(tp, a, l, cpi, type, type == PlaneType.Y_NO_DC ? 0 : 1);
   }

   static void stuff1st_order_buv(FullGenArrPointer<TokenExtra> tp, FullGetSetPointer a, FullGetSetPointer l, Compressor cpi) {
      stuffOrderHelper(tp, a, l, cpi, PlaneType.UV, 0);
   }

   static void vp8_stuff_mb(Compressor cpi, Macroblock x, FullGenArrPointer<TokenExtra> t) {
      MacroblockD xd = x.e_mbd;
      EntropyPlanes A = xd.above_context.get();
      EntropyPlanes L = xd.left_context;
      PlaneType plane_type = PlaneType.Y_WITH_DC;
      if (xd.hasSecondOrder()) {
         stuff2nd_order_b(t, A.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[24]), L.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[24]), cpi);
         plane_type = PlaneType.Y_NO_DC;
      }

      for (int b = 0; b < 16; b++) {
         stuff1st_order_b(t, A.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[b]), L.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[b]), plane_type, cpi);
      }

      for (int var8 = 16; var8 < 24; var8++) {
         stuff1st_order_buv(t, A.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[var8]), L.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[var8]), cpi);
      }
   }

   static boolean mb_is_skippable(MacroblockD x, boolean has_y2_block) {
      boolean skip = true;
      int i = 0;
      int imax = 24;
      if (has_y2_block) {
         imax++;

         for (i = 0; i < 16 && skip; i++) {
            skip &= x.eobs.getRel(i) < 2;
         }
      }

      while (i < imax && skip) {
         skip &= x.eobs.getRel(i) == 0;
         i++;
      }

      return skip;
   }

   static void vp8_fix_contexts(MacroblockD x) {
      if (x.hasSecondOrder()) {
         x.above_context.get().panes.memset(0, (short)0, x.above_context.get().panes.size());
         x.left_context.panes.memset(0, (short)0, x.left_context.panes.size());
      } else {
         x.above_context.get().panes.memset(0, (short)0, x.above_context.get().panes.size() - 1);
         x.left_context.panes.memset(0, (short)0, x.left_context.panes.size() - 1);
      }
   }

   static void vp8_tokenize_mb(Compressor cpi, Macroblock x, FullGenArrPointer<TokenExtra> t) {
      MacroblockD xd = x.e_mbd;
      boolean has_y2_block = xd.hasSecondOrder();
      boolean hasSkip = mb_is_skippable(xd, has_y2_block);
      xd.mode_info_context.get().mbmi.mb_skip_coeff = hasSkip;
      if (hasSkip) {
         if (!cpi.common.mb_no_coeff_skip) {
            vp8_stuff_mb(cpi, x, t);
         } else {
            vp8_fix_contexts(xd);
            x.skip_true_count++;
         }
      } else {
         PlaneType plane_type = PlaneType.Y_WITH_DC;
         if (has_y2_block) {
            tokenize2nd_order_b(x, t, cpi);
            plane_type = PlaneType.Y_NO_DC;
         }

         tokenize1st_order_b(x, t, plane_type, cpi);
      }
   }
}
