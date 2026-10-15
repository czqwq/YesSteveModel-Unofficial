package rip.ysm.imagestream.webp.enc;

import java.util.EnumMap;
import rip.ysm.imagestream.internal.LogWriter;

final class BitStream {
   static final int[] VP_8_CX_BASE_SKIP_FALSE_PROB = new int[]{
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      251,
      248,
      244,
      240,
      236,
      232,
      229,
      225,
      221,
      217,
      213,
      208,
      204,
      199,
      194,
      190,
      187,
      183,
      179,
      175,
      172,
      168,
      164,
      160,
      157,
      153,
      149,
      145,
      142,
      138,
      134,
      130,
      127,
      124,
      120,
      117,
      114,
      110,
      107,
      104,
      101,
      98,
      95,
      92,
      89,
      86,
      83,
      80,
      77,
      74,
      71,
      68,
      65,
      62,
      59,
      56,
      53,
      50,
      47,
      44,
      41,
      38,
      35,
      32,
      30,
      28,
      26,
      24,
      22,
      20,
      18,
      16
   };
   private static final int[][][][] VP_8_COEF_UPDATE_PROBS = new int[][][][]{
      {
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {176, 246, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {223, 241, 252, 255, 255, 255, 255, 255, 255, 255, 255},
                  {249, 253, 253, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 244, 252, 255, 255, 255, 255, 255, 255, 255, 255},
                  {234, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 246, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {239, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 248, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {251, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {251, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 253, 255, 254, 255, 255, 255, 255, 255, 255},
                  {250, 255, 254, 255, 254, 255, 255, 255, 255, 255, 255},
                  {254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            }
      },
      {
            {
                  {217, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {225, 252, 241, 253, 255, 255, 254, 255, 255, 255, 255},
                  {234, 250, 241, 250, 253, 255, 253, 254, 255, 255, 255}
            },
            {
                  {255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {223, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {238, 253, 254, 254, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 248, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {249, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 253, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {247, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {252, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {250, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            }
      },
      {
            {
                  {186, 251, 250, 255, 255, 255, 255, 255, 255, 255, 255},
                  {234, 251, 244, 254, 255, 255, 255, 255, 255, 255, 255},
                  {251, 251, 243, 253, 254, 255, 254, 255, 255, 255, 255}
            },
            {
                  {255, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {236, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {251, 253, 253, 254, 254, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            }
      },
      {
            {
                  {248, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {250, 254, 252, 254, 255, 255, 255, 255, 255, 255, 255},
                  {248, 254, 249, 253, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 253, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {246, 253, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {252, 254, 251, 254, 254, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 254, 252, 255, 255, 255, 255, 255, 255, 255, 255},
                  {248, 254, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {253, 255, 254, 254, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 251, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {245, 251, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {253, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 251, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {252, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 252, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {249, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 253, 255, 255, 255, 255, 255, 255, 255, 255},
                  {250, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            },
            {
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255},
                  {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}
            }
      }
   };
   static final Token[] vp8_coef_encodings = new Token[TokenAlphabet.entropyTokenCount];

   private BitStream() {
   }

   static void kfwrite_ymode(BitEncoder bc, MBPredictionMode m) {
      TreeWriter.vp8_write_token(bc, EntropyMode.vp8_kf_ymode_tree, EntropyMode.vp8_kf_ymode_prob, Token.vp8_kf_ymode_encodings[m.ordinal()]);
   }

   static void write_uv_mode(BitEncoder bc, MBPredictionMode m) {
      TreeWriter.vp8_write_token(bc, EntropyMode.vp8_uv_mode_tree, EntropyMode.vp8_kf_uv_mode_prob, Token.vp8_uv_mode_encodings[m.ordinal()]);
   }

   static void write_bmode(BitEncoder bc, int m, GetPointer p) {
      TreeWriter.vp8_write_token(bc, EntropyMode.vp8_bmode_tree, p, Token.vp8_bmode_encodings[m]);
   }

   private static void encodeTokenPartShort(BitEncoder w, int v, int n, int i, short[] probs, short[] tree) {
      do {
         int bb = v >> --n & 1;
         w.vp8_encode_bool(bb != 0, probs[i >> 1]);
         i = tree[i + bb];
      } while (n != 0);
   }

   static void vp8_pack_tokens(BitEncoder w, TokenList p) {
      for (FullGenArrPointer<TokenExtra> curr = p.start.shallowCopy(); !curr.equals(p.stop); curr.inc()) {
         TokenExtra text = curr.get();
         TokenAlphabet t = text.Token;
         int i;
         int n;
         if (text.skip_eob_node) {
            n = t.coefEncLen - 1;
            i = 2;
         } else {
            n = t.coefEncLen;
            i = 0;
         }

         encodeTokenPartShort(w, t.coefEncValue, n, i, text.context_tree, Entropy.vp8_coef_tree_shorts);
         if (t.base_val != 0) {
            int e = text.Extra;
            int L = t.len;
            if (L != 0) {
               encodeTokenPartShort(w, e >> 1, L, 0, t.prob, t.tree);
            }

            w.vp8_encode_extra(e);
         }
      }
   }

   static void write_partition_size(FullGetSetPointer cx_data, int size) {
      cx_data.set((short)(size & 0xFF));
      cx_data.setRel(1, (short)(size >> 8 & 0xFF));
      cx_data.setRel(2, (short)(size >> 16 & 0xFF));
   }

   static void pack_tokens_into_partitions(Compressor cpi, FullGetSetPointer cx_data, int num_part) {
      for (int i = 0; i < num_part; i++) {
         BitEncoder w = cpi.bc[i + 1];
         w.vp8_start_encode(cx_data);

         for (int mb_row = i; mb_row < cpi.common.mb_rows; mb_row += num_part) {
            vp8_pack_tokens(w, cpi.tplist[mb_row]);
         }

         w.vp8_stop_encode();
         cx_data.incBy(w.getPos());
      }

      for (int i = 0; i < num_part; i++) {
         for (int mb_row = i; mb_row < cpi.common.mb_rows; mb_row += num_part) {
            cpi.tplist[mb_row].start.arr.clear();
            cpi.tplist[mb_row].stop.arr.clear();
         }
      }
   }

   static void write_mb_features(BitEncoder w, MBModeInfo mi, MacroblockD x) {
      if (x.segmentation_enabled != 0 && x.update_mb_segmentation_map) {
         switch (mi.segment_id) {
            case 1:
               w.vp8_encode_bool(false, x.mb_segment_tree_probs[0]);
               w.vp8_encode_bool(true, x.mb_segment_tree_probs[1]);
               break;
            case 2:
               w.vp8_encode_bool(true, x.mb_segment_tree_probs[0]);
               w.vp8_encode_bool(false, x.mb_segment_tree_probs[2]);
               break;
            case 3:
               w.vp8_encode_bool(true, x.mb_segment_tree_probs[0]);
               w.vp8_encode_bool(true, x.mb_segment_tree_probs[2]);
               break;
            default:
               w.vp8_encode_bool(false, x.mb_segment_tree_probs[0]);
               w.vp8_encode_bool(false, x.mb_segment_tree_probs[1]);
         }
      }
   }

   static void vp8_convert_rfct_to_prob(Compressor cpi) {
      cpi.vp8_convert_rfct_to_prob();
   }

   static void write_kfmodes(Compressor cpi) {
      BitEncoder bc = cpi.bc[0];
      CommonData c = cpi.common;
      FullGenArrPointer<ModeInfo> m = c.mi;
      int mPos = c.mi.getPos();
      int mb_row = -1;
      int prob_skip_false = 0;
      if (c.mb_no_coeff_skip) {
         int total_mbs = c.mb_rows * c.mb_cols;
         prob_skip_false = (total_mbs - cpi.mb.skip_true_count) * 256 / total_mbs;
         if (prob_skip_false <= 1) {
            prob_skip_false = 1;
         }

         if (prob_skip_false >= 255) {
            prob_skip_false = 255;
         }

         cpi.prob_skip_false = prob_skip_false;
         TreeWriter.vp8_write_literal(bc, prob_skip_false, 8);
      }

      while (++mb_row < c.mb_rows) {
         for (int mb_col = -1; ++mb_col < c.mb_cols; write_uv_mode(bc, m.getAndInc().mbmi.uv_mode)) {
            MBPredictionMode ym = m.get().mbmi.mode;
            if (cpi.mb.e_mbd.update_mb_segmentation_map) {
               write_mb_features(bc, m.get().mbmi, cpi.mb.e_mbd);
            }

            if (c.mb_no_coeff_skip) {
               bc.vp8_encode_bool(m.get().mbmi.mb_skip_coeff, prob_skip_false);
            }

            kfwrite_ymode(bc, ym);
            if (ym == MBPredictionMode.B_PRED) {
               int mis = c.mode_info_stride;
               int i = 0;

               while (true) {
                  int A = FindNearMV.above_block_mode(m, i, mis);
                  int L = FindNearMV.left_block_mode(m, i);
                  int bm = m.get().bmi[i].as_mode();
                  write_bmode(bc, bm, new GetPointer(W.keyFrameSubblockModeProb[A][L], 0));
                  if (++i >= 16) {
                     break;
                  }
               }
            }
         }

         m.inc();
      }

      m.setPos(mPos);
   }

   static int prob_update_savings(int[] ct, int oldp, int newp, int upd) {
      int old_b = TreeWriter.vp8_cost_branch(ct, oldp);
      int new_b = TreeWriter.vp8_cost_branch(ct, newp);
      int update_b = 8 + (TreeWriter.vp8_cost_one(upd) - TreeWriter.vp8_cost_zero(upd) >> 8);
      return old_b - new_b - update_b;
   }

   static int default_coef_context_savings(Compressor cpi) {
      Macroblock x = cpi.mb;
      int savings = 0;
      int i = 0;

      do {
         int j = 0;

         do {
            int k = 0;

            do {
               int t = 0;
               vp8_tree_probs_from_distribution(cpi.frame_coef_probs[i][j][k], cpi.frame_branch_ct[i][j][k], x.coef_counts[i][j][k]);

               do {
                  int[] ct = cpi.frame_branch_ct[i][j][k][t];
                  int newp = cpi.frame_coef_probs[i][j][k][t];
                  int oldp = cpi.common.fc.coef_probs[i][j][k][t];
                  int upd = VP_8_COEF_UPDATE_PROBS[i][j][k][t];
                  int s = prob_update_savings(ct, oldp, newp, upd);
                  if (s > 0) {
                     savings += s;
                  }
               } while (++t < 11);
            } while (++k < 3);
         } while (++j < 8);
      } while (++i < 4);

      return savings;
   }

   static void vp8_calc_ref_frame_costs(int[] ref_frame_cost, int prob_intra, int prob_last, int prob_garf) {
      ref_frame_cost[MVReferenceFrame.INTRA_FRAME.ordinal()] = TreeWriter.vp8_cost_zero(prob_intra);
      ref_frame_cost[MVReferenceFrame.LAST_FRAME.ordinal()] = TreeWriter.vp8_cost_one(prob_intra) + TreeWriter.vp8_cost_zero(prob_last);
      ref_frame_cost[MVReferenceFrame.GOLDEN_FRAME.ordinal()] = TreeWriter.vp8_cost_one(prob_intra)
         + TreeWriter.vp8_cost_one(prob_last)
         + TreeWriter.vp8_cost_zero(prob_garf);
      ref_frame_cost[MVReferenceFrame.ALTREF_FRAME.ordinal()] = TreeWriter.vp8_cost_one(prob_intra)
         + TreeWriter.vp8_cost_one(prob_last)
         + TreeWriter.vp8_cost_one(prob_garf);
   }

   static int vp8_estimate_entropy_savings(Compressor cpi) {
      int savings = 0;
      ReferenceCounts rf = cpi.mb.sumReferenceCounts();
      EnumMap<MVReferenceFrame, Integer> rfct = cpi.mb.count_mb_ref_frame_usage;
      int[] refFrameCost = new int[MVReferenceFrame.count];
      if (cpi.common.frame_type != 0) {
         int new_intra;
         if ((new_intra = rf.intra * 255 / (rf.intra + rf.inter)) == 0) {
            new_intra = 1;
         }

         int new_last = rf.inter != 0 ? rfct.get(MVReferenceFrame.LAST_FRAME) * 255 / rf.inter : 128;
         int new_garf = rfct.get(MVReferenceFrame.GOLDEN_FRAME) + rfct.get(MVReferenceFrame.ALTREF_FRAME) != 0
            ? rfct.get(MVReferenceFrame.GOLDEN_FRAME) * 255 / (rfct.get(MVReferenceFrame.GOLDEN_FRAME) + rfct.get(MVReferenceFrame.ALTREF_FRAME))
            : 128;
         vp8_calc_ref_frame_costs(refFrameCost, new_intra, new_last, new_garf);
         int newTotal = rfct.get(MVReferenceFrame.INTRA_FRAME) * refFrameCost[MVReferenceFrame.INTRA_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.LAST_FRAME) * refFrameCost[MVReferenceFrame.LAST_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.GOLDEN_FRAME) * refFrameCost[MVReferenceFrame.GOLDEN_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.ALTREF_FRAME) * refFrameCost[MVReferenceFrame.ALTREF_FRAME.ordinal()];
         vp8_calc_ref_frame_costs(refFrameCost, cpi.prob_intra_coded, cpi.prob_last_coded, cpi.prob_gf_coded);
         int oldtotal = rfct.get(MVReferenceFrame.INTRA_FRAME) * refFrameCost[MVReferenceFrame.INTRA_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.LAST_FRAME) * refFrameCost[MVReferenceFrame.LAST_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.GOLDEN_FRAME) * refFrameCost[MVReferenceFrame.GOLDEN_FRAME.ordinal()]
            + rfct.get(MVReferenceFrame.ALTREF_FRAME) * refFrameCost[MVReferenceFrame.ALTREF_FRAME.ordinal()];
         savings += (oldtotal - newTotal) / 256;
      }

      return savings + default_coef_context_savings(cpi);
   }

   private static void vp8UpdateCoefProbs(Compressor cpi) {
      int i = 0;
      BitEncoder w = cpi.bc[0];
      int[] prev_coef_savings = new int[11];

      do {
         int j = 0;

         do {
            int k = 0;
            CUtils.vp8_zero(prev_coef_savings);
            if (cpi.oxcf.error_resilient_mode) {
               for (int var12 = 0; var12 < 3; var12++) {
                  for (int t = 0; t < 11; t++) {
                     int[] ct = cpi.frame_branch_ct[i][j][var12][t];
                     int newp = cpi.frame_coef_probs[i][j][var12][t];
                     int oldp = cpi.common.fc.coef_probs[i][j][var12][t];
                     int upd = VP_8_COEF_UPDATE_PROBS[i][j][var12][t];
                     prev_coef_savings[t] += prob_update_savings(ct, oldp, newp, upd);
                  }
               }

               k = 0;
            }

            do {
               int t = 0;

               do {
                  short newp = cpi.frame_coef_probs[i][j][k][t];
                  int Pold = cpi.common.fc.coef_probs[i][j][k][t];
                  int upd = VP_8_COEF_UPDATE_PROBS[i][j][k][t];
                  int s = prev_coef_savings[t];
                  boolean u = false;
                  if (!cpi.oxcf.error_resilient_mode) {
                     s = prob_update_savings(cpi.frame_branch_ct[i][j][k][t], Pold, newp, upd);
                  }

                  if (s > 0) {
                     u = true;
                  }

                  if (cpi.oxcf.error_resilient_mode && cpi.common.frame_type == 0 && newp != Pold) {
                     u = true;
                  }

                  w.vp8_encode_bool(u, upd);
                  if (u) {
                     cpi.common.fc.coef_probs[i][j][k][t] = newp;
                     TreeWriter.vp8_write_literal(w, newp, 8);
                  }
               } while (++t < 11);
            } while (++k < 3);
         } while (++j < 8);
      } while (++i < 4);
   }

   private static void put_delta_q(BitEncoder bc, int delta_q) {
      if (delta_q != 0) {
         bc.vp8_write_bit(true);
         TreeWriter.vp8_write_literal(bc, Math.abs(delta_q), 4);
         bc.vp8_write_bit(delta_q < 0);
      } else {
         bc.vp8_write_bit(false);
      }
   }

   static int vp8_pack_bitstream(Compressor cpi, FullGetSetPointer dest) {
      Header oh = new Header();
      CommonData pc = cpi.common;
      BitEncoder[] bc = cpi.bc;
      MacroblockD xd = cpi.mb.e_mbd;
      int extra_bytes_packed = 0;
      FullGetSetPointer cx_data = dest.shallowCopy();
      int mbfeaturedatabitsIdx = 0;
      oh.show_frame = pc.show_frame;
      oh.type = pc.frame_type;
      oh.version = pc.getVersion();
      oh.first_partition_length_in_bytes = 0;
      cx_data.incBy(3);
      if (oh.type == 0) {
         cx_data.setAndInc((short)157);
         cx_data.setAndInc((short)1);
         cx_data.setAndInc((short)42);
         int v = CommonData.horiz_scale.ordinal() << 14 | pc.Width;
         cx_data.setAndInc((short)(v & 0xFF));
         cx_data.setAndInc((short)(v >> 8));
         v = CommonData.vert_scale.ordinal() << 14 | pc.Height;
         cx_data.setAndInc((short)(v & 0xFF));
         cx_data.setAndInc((short)(v >> 8));
         extra_bytes_packed = 7;
         bc[0].vp8_start_encode(cx_data);
         bc[0].vp8_write_bit(false);
         bc[0].vp8_write_bit(false);
      } else {
         bc[0].vp8_start_encode(cx_data);
      }

      bc[0].vp8_write_bit(xd.segmentation_enabled != 0);
      if (xd.segmentation_enabled != 0) {
         compressSegmentEnabled(xd, bc, 0);
      }

      bc[0].vp8_write_bit(pc.filter_type == 1);
      TreeWriter.vp8_write_literal(bc[0], pc.filter_level, 6);
      TreeWriter.vp8_write_literal(bc[0], pc.sharpness_level, 3);
      bc[0].vp8_write_bit(xd.mode_ref_lf_delta_enabled);
      if (xd.mode_ref_lf_delta_enabled) {
         compressDeltaEnabled(cpi, xd, bc);
      }

      TreeWriter.vp8_write_literal(bc[0], pc.multi_token_partition, 2);
      TreeWriter.vp8_write_literal(bc[0], pc.base_qindex, 7);
      put_delta_q(bc[0], pc.delta_q.get(CommonData.Quant.Y1).get(CommonData.Comp.DC));
      put_delta_q(bc[0], pc.delta_q.get(CommonData.Quant.Y2).get(CommonData.Comp.DC));
      put_delta_q(bc[0], pc.delta_q.get(CommonData.Quant.Y2).get(CommonData.Comp.AC));
      put_delta_q(bc[0], pc.delta_q.get(CommonData.Quant.UV).get(CommonData.Comp.DC));
      put_delta_q(bc[0], pc.delta_q.get(CommonData.Quant.UV).get(CommonData.Comp.AC));
      if (pc.frame_type != 0) {
         bc[0].vp8_write_bit(pc.refresh_golden_frame);
         bc[0].vp8_write_bit(pc.refresh_alt_ref_frame);
         if (!pc.refresh_golden_frame) {
            TreeWriter.vp8_write_literal(bc[0], pc.copy_buffer_to_gf, 2);
         }

         if (!pc.refresh_alt_ref_frame) {
            TreeWriter.vp8_write_literal(bc[0], pc.copy_buffer_to_arf, 2);
         }

         bc[0].vp8_write_bit(pc.ref_frame_sign_bias.get(MVReferenceFrame.GOLDEN_FRAME));
         bc[0].vp8_write_bit(pc.ref_frame_sign_bias.get(MVReferenceFrame.ALTREF_FRAME));
      }

      if (cpi.oxcf.error_resilient_mode) {
         pc.refresh_entropy_probs = pc.frame_type == 0;
      }

      bc[0].vp8_write_bit(pc.refresh_entropy_probs);
      if (pc.frame_type != 0) {
         bc[0].vp8_write_bit(pc.refresh_last_frame);
      }

      if (!pc.refresh_entropy_probs) {
         cpi.common.lfc = new FrameContext(cpi.common.fc);
      }

      vp8UpdateCoefProbs(cpi);
      bc[0].vp8_write_bit(pc.mb_no_coeff_skip);
      if (pc.frame_type == 0) {
         write_kfmodes(cpi);
      } else {
         LogWriter.writeLog("should not be called");
      }

      bc[0].vp8_stop_encode();
      cx_data.incBy(bc[0].getPos());
      oh.first_partition_length_in_bytes = cpi.bc[0].getPos();
      short[] ohAsbytes = oh.asThreeBytes();
      dest.memcopyin(0, ohAsbytes, 0, ohAsbytes.length);
      int size = 3 + extra_bytes_packed + cpi.bc[0].getPos();
      cpi.partition_sz[0] = size;
      int var16;
      if (pc.multi_token_partition != 0) {
         int num_part = 1 << pc.multi_token_partition;
         int var15;
         cpi.partition_sz[0] = cpi.partition_sz[0] + (var15 = size + 3 * (num_part - 1));
         pack_tokens_into_partitions(cpi, cx_data.shallowCopyWithPosInc(3 * (num_part - 1)), num_part);

         int i;
         for (i = 1; i < num_part; i++) {
            cpi.partition_sz[i] = cpi.bc[i].getPos();
            write_partition_size(cx_data, cpi.partition_sz[i]);
            cx_data.incBy(3);
            var15 += cpi.partition_sz[i];
         }

         cpi.partition_sz[i] = cpi.bc[i].getPos();
         var16 = var15 + cpi.partition_sz[i];
      } else {
         cpi.bc[1].vp8_start_encode(cx_data);
         TokenList tlist = new TokenList();
         tlist.start = cpi.tok;
         tlist.stop = cpi.tok.shallowCopyWithPosInc(cpi.tok_count);
         vp8_pack_tokens(cpi.bc[1], tlist);
         cpi.bc[1].vp8_stop_encode();
         cpi.partition_sz[1] = var16 = size + cpi.bc[1].getPos();
      }

      return var16;
   }

   private static void compressSegmentEnabled(MacroblockD xd, BitEncoder[] bc, int mbfeaturedatabitsIdx) {
      bc[0].vp8_write_bit(xd.update_mb_segmentation_map);
      bc[0].vp8_write_bit(xd.update_mb_segmentation_data);
      if (xd.update_mb_segmentation_data) {
         bc[0].vp8_write_bit(xd.mb_segement_abs_delta);

         for (int i = 0; i < MBLvlFeatures.featureCount; i++) {
            for (int j = 0; j < 4; j++) {
               int Data = xd.segment_feature_data[i][j];
               if (Data != 0) {
                  bc[0].vp8_write_bit(true);
                  if (Data < 0) {
                     Data = -Data;
                     TreeWriter.vp8_write_literal(bc[0], Data, Entropy.vp8_mb_feature_data_bits[mbfeaturedatabitsIdx + i]);
                     bc[0].vp8_write_bit(true);
                  } else {
                     TreeWriter.vp8_write_literal(bc[0], Data, Entropy.vp8_mb_feature_data_bits[mbfeaturedatabitsIdx + i]);
                     bc[0].vp8_write_bit(false);
                  }
               } else {
                  bc[0].vp8_write_bit(false);
               }
            }
         }
      }

      if (xd.update_mb_segmentation_map) {
         for (int i = 0; i < 3; i++) {
            int Data = xd.mb_segment_tree_probs[i];
            if (Data != 255) {
               bc[0].vp8_write_bit(true);
               TreeWriter.vp8_write_literal(bc[0], Data, 8);
            } else {
               bc[0].vp8_write_bit(false);
            }
         }
      }
   }

   private static void compressDeltaEnabled(Compressor cpi, MacroblockD xd, BitEncoder[] bc) {
      boolean send_update = xd.mode_ref_lf_delta_update || cpi.oxcf.error_resilient_mode;
      bc[0].vp8_write_bit(send_update);
      if (send_update) {
         byte[][] updater = new byte[][]{xd.ref_lf_deltas, xd.mode_lf_deltas};
         byte[][] lastUpd = new byte[][]{xd.last_ref_lf_deltas, xd.last_mode_lf_deltas};

         for (int k = 0; k < updater.length; k++) {
            for (int i = 0; i < 4; i++) {
               int Data = updater[k][i];
               if (Data == lastUpd[k][i] && !cpi.oxcf.error_resilient_mode) {
                  bc[0].vp8_write_bit(false);
               } else {
                  lastUpd[k][i] = updater[k][i];
                  bc[0].vp8_write_bit(true);
                  boolean sign = false;
                  if (Data < 0) {
                     Data = -Data;
                     sign = true;
                  }

                  TreeWriter.vp8_write_literal(bc[0], Data & 63, 6);
                  bc[0].vp8_write_bit(sign);
               }
            }
         }
      }
   }

   private static void vp8_tree_probs_from_distribution(short[] probs, int[][] branch_ct, int[] num_events) {
      int tree_len = TokenAlphabet.entropyTokenCount - 1;
      int t = 0;
      branch_counts(TokenAlphabet.entropyTokenCount, vp8_coef_encodings, Entropy.vp8_coef_tree, branch_ct, num_events);

      do {
         int[] c = branch_ct[t];
         int tot = c[0] + c[1];
         if (tot != 0) {
            short p = (short)((c[0] * 256L + (tot >> 1)) / tot);
            probs[t] = CUtils.clamp(p, (short)1, (short)255);
         } else {
            probs[t] = 128;
         }
      } while (++t < tree_len);
   }

   private static void branch_counts(int n, Token[] tok, GetPointer tree, int[][] branch_ct, int[] num_events) {
      int tree_len = n - 1;
      int t = 0;

      do {
         branch_ct[t][0] = branch_ct[t][1] = 0;
      } while (++t < tree_len);

      t = 0;

      do {
         int L = tok[t].len;
         int enc = tok[t].value;
         int ct = num_events[t];
         int i = 0;

         do {
            int b = enc >> --L & 1;
            int j = i >> 1;
            branch_ct[j][b] = branch_ct[j][b] + ct;
            i = tree.getRel(i + b);
         } while (i > 0);
      } while (++t < n);
   }

   static {
      int i = 0;

      for (TokenAlphabet ta : TokenAlphabet.values()) {
         vp8_coef_encodings[i++] = new Token(ta.coefEncValue, ta.coefEncLen);
      }
   }
}
