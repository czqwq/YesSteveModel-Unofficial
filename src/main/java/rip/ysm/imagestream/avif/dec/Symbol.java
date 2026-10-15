package rip.ysm.imagestream.avif.dec;

class Symbol {
   int[][][] partition_cdf = new int[5][4][11];
   int[][][][] palette_color_index_cdf = new int[2][7][5][9];
   final int[][] segment_id_cdf = new int[3][9];
   final int[][] use_predicted_segment_id_cdf = new int[3][3];
   final int[][] skip_cdf = new int[3][3];
   final int[][] skip_mode_cdf = new int[3][3];
   final int[] delta_q_cdf = new int[5];
   final int[] delta_lf_cdf = new int[5];
   final int[][] delta_lf_multi_cdf = new int[4][5];
   final int[] intra_block_copy_cdf = new int[3];
   final int[][][] intra_frame_y_mode_cdf = new int[5][5][14];
   final int[][] y_mode_cdf = new int[4][14];
   final int[][] angle_delta_cdf = new int[8][8];
   final int[][][] uv_mode_cdf = new int[2][13][15];
   final int[] cfl_alpha_signs_cdf = new int[9];
   final int[][] cfl_alpha_cdf = new int[6][17];
   final int[][] use_filter_intra_cdf = new int[22][3];
   final int[] filter_intra_mode_cdf = new int[6];
   final int[][][] tx_depth_cdf = new int[4][3][4];
   final int[][] tx_split_cdf = new int[21][3];
   final int[][][] all_zero_cdf = new int[5][13][3];
   final int[][][] inter_tx_type_cdf = new int[3][4][17];
   final int[][][][] intra_tx_type_cdf = new int[2][4][13][17];
   final int[][][] eob_pt_16_cdf = new int[2][2][6];
   final int[][][] eob_pt_32_cdf = new int[2][2][7];
   final int[][][] eob_pt_64_cdf = new int[2][2][8];
   final int[][][] eob_pt_128_cdf = new int[2][2][9];
   final int[][][] eob_pt_256_cdf = new int[2][2][10];
   final int[][] eob_pt_512_cdf = new int[2][11];
   final int[][] eob_pt_1024_cdf = new int[2][12];
   final int[][][][] eob_extra_cdf = new int[5][2][9][3];
   final int[][][][] coeff_base_eob_cdf = new int[5][2][4][4];
   final int[][][][] coeff_base_cdf = new int[5][2][42][5];
   final int[][][][] coeff_base_range_cdf = new int[5][2][21][5];
   final int[][][] dc_sign_cdf = new int[2][3][3];
   final int[] restoration_type_cdf = new int[4];
   final int[] use_wiener_cdf = new int[3];
   final int[] use_sgrproj_cdf = new int[3];
   final int[][][] has_palette_y_cdf = new int[7][3][3];
   final int[][] palette_y_size_cdf = new int[7][8];
   final int[][] has_palette_uv_cdf = new int[2][3];
   final int[][] palette_uv_size_cdf = new int[7][8];
   final int[][] is_inter_cdf = new int[4][3];
   final int[][] use_compound_reference_cdf = new int[5][3];
   final int[][] compound_reference_type_cdf = new int[5][3];
   final int[][][][] compound_reference_cdf = new int[2][3][3][3];
   final int[][][] compound_backward_reference_cdf = new int[3][2][3];
   final int[][][] single_reference_cdf = new int[3][6][3];
   final int[][] compound_prediction_mode_cdf = new int[8][9];
   final int[][] new_mv_cdf = new int[6][3];
   final int[][] zero_mv_cdf = new int[2][3];
   final int[][] reference_mv_cdf = new int[6][3];
   final int[][] ref_mv_index_cdf = new int[3][3];
   final int[][] is_inter_intra_cdf = new int[3][3];
   final int[][] inter_intra_mode_cdf = new int[3][5];
   final int[][] is_wedge_inter_intra_cdf = new int[23][3];
   final int[][] wedge_index_cdf = new int[22][17];
   final int[][] use_obmc_cdf = new int[22][3];
   final int[][] motion_mode_cdf = new int[22][4];
   final int[][] is_explicit_compound_type_cdf = new int[6][3];
   final int[][] is_compound_type_average_cdf = new int[6][3];
   final int[][] compound_type_cdf = new int[22][3];
   final int[][] interpolation_filter_cdf = new int[16][4];
   final int[][] mv_joint_cdf = new int[2][5];
   final int[][][] mv_sign_cdf = new int[2][2][3];
   final int[][][] mv_class_cdf = new int[2][2][12];
   final int[][][] mv_class0_bit_cdf = new int[2][2][3];
   final int[][][][] mv_class0_fraction_cdf = new int[2][2][2][5];
   final int[][][] mv_class0_high_precision_cdf = new int[2][2][3];
   final int[][][][] mv_bit_cdf = new int[2][2][10][3];
   final int[][][] mv_fraction_cdf = new int[2][2][5];
   final int[][][] mv_high_precision_cdf = new int[2][2][3];

   void initialize(int base_quantizer_index) {
      COPY(Sym1.kDefaultPartitionCdf, this.partition_cdf);
      COPY(Sym1.kDefaultSkipCdf, this.skip_cdf);
      COPY(Sym1.kDefaultSkipModeCdf, this.skip_mode_cdf);
      COPY(Sym1.kDefaultSegmentIdCdf, this.segment_id_cdf);
      COPY(Sym1.kDefaultUsePredictedSegmentIdCdf, this.use_predicted_segment_id_cdf);
      COPY(Sym1.kDefaultDeltaQCdf, this.delta_q_cdf);
      COPY(Sym1.kDefaultDeltaQCdf, this.delta_lf_cdf);

      for (int[] delta_lf_multi_cdf_entry : this.delta_lf_multi_cdf) {
         COPY(Sym1.kDefaultDeltaQCdf, delta_lf_multi_cdf_entry);
      }

      COPY(Sym1.kDefaultIntraBlockCopyCdf, this.intra_block_copy_cdf);
      COPY(Sym1.kDefaultIntraFrameYModeCdf, this.intra_frame_y_mode_cdf);
      COPY(Sym1.kDefaultYModeCdf, this.y_mode_cdf);
      COPY(Sym1.kDefaultAngleDeltaCdf, this.angle_delta_cdf);
      COPY(Sym1.kDefaultUVModeCdf, this.uv_mode_cdf);
      COPY(Sym1.kDefaultCflAlphaSignsCdf, this.cfl_alpha_signs_cdf);
      COPY(Sym1.kDefaultCflAlphaCdf, this.cfl_alpha_cdf);
      COPY(Sym1.kDefaultUseFilterIntraCdf, this.use_filter_intra_cdf);
      COPY(Sym1.kDefaultFilterIntraModeCdf, this.filter_intra_mode_cdf);
      COPY(Sym1.kDefaultTxDepthCdf, this.tx_depth_cdf);
      COPY(Sym1.kDefaultTxSplitCdf, this.tx_split_cdf);
      COPY(Sym1.kDefaultInterTxTypeCdf, this.inter_tx_type_cdf);
      COPY(Sym1.kDefaultIntraTxTypeCdf, this.intra_tx_type_cdf);
      COPY(Sym1.kDefaultRestorationTypeCdf, this.restoration_type_cdf);
      COPY(Sym1.kDefaultUseWienerCdf, this.use_wiener_cdf);
      COPY(Sym1.kDefaultUseSgrProjCdf, this.use_sgrproj_cdf);
      COPY(Sym1.kDefaultHasPaletteYCdf, this.has_palette_y_cdf);
      COPY(Sym1.kDefaultPaletteYSizeCdf, this.palette_y_size_cdf);
      COPY(Sym1.kDefaultHasPaletteUVCdf, this.has_palette_uv_cdf);
      COPY(Sym1.kDefaultPaletteUVSizeCdf, this.palette_uv_size_cdf);
      COPY(Sym1.kDefaultPaletteColorIndexCdf, this.palette_color_index_cdf);
      COPY(Sym1.kDefaultIsInterCdf, this.is_inter_cdf);
      COPY(Sym1.kDefaultUseCompoundReferenceCdf, this.use_compound_reference_cdf);
      COPY(Sym1.kDefaultCompoundReferenceTypeCdf, this.compound_reference_type_cdf);
      COPY(Sym1.kDefaultCompoundReferenceCdf, this.compound_reference_cdf);
      COPY(Sym1.kDefaultCompoundBackwardReferenceCdf, this.compound_backward_reference_cdf);
      COPY(Sym1.kDefaultSingleReferenceCdf, this.single_reference_cdf);
      COPY(Sym1.kDefaultCompoundPredictionModeCdf, this.compound_prediction_mode_cdf);
      COPY(Sym1.kDefaultNewMvCdf, this.new_mv_cdf);
      COPY(Sym1.kDefaultZeroMvCdf, this.zero_mv_cdf);
      COPY(Sym1.kDefaultReferenceMvCdf, this.reference_mv_cdf);
      COPY(Sym1.kDefaultRefMvIndexCdf, this.ref_mv_index_cdf);
      COPY(Sym1.kDefaultIsInterIntraCdf, this.is_inter_intra_cdf);
      COPY(Sym1.kDefaultInterIntraModeCdf, this.inter_intra_mode_cdf);
      COPY(Sym1.kDefaultIsWedgeInterIntraCdf, this.is_wedge_inter_intra_cdf);
      COPY(Sym1.kDefaultWedgeIndexCdf, this.wedge_index_cdf);
      COPY(Sym1.kDefaultUseObmcCdf, this.use_obmc_cdf);
      COPY(Sym1.kDefaultMotionModeCdf, this.motion_mode_cdf);
      COPY(Sym1.kDefaultIsExplicitCompoundTypeCdf, this.is_explicit_compound_type_cdf);
      COPY(Sym1.kDefaultIsCompoundTypeAverageCdf, this.is_compound_type_average_cdf);
      COPY(Sym1.kDefaultCompoundTypeCdf, this.compound_type_cdf);
      COPY(Sym1.kDefaultInterpolationFilterCdf, this.interpolation_filter_cdf);

      for (int i = 0; i < 2; i++) {
         COPY(Sym1.kDefaultMvJointCdf, this.mv_joint_cdf[i]);

         for (int j = 0; j < 2; j++) {
            COPY(Sym1.kDefaultMvSignCdf, this.mv_sign_cdf[i][j]);
            COPY(Sym1.kDefaultMvClassCdf, this.mv_class_cdf[i][j]);
            COPY(Sym1.kDefaultMvClass0BitCdf, this.mv_class0_bit_cdf[i][j]);
            COPY(Sym1.kDefaultMvClass0FractionCdf, this.mv_class0_fraction_cdf[i][j]);
            COPY(Sym1.kDefaultMvClass0HighPrecisionCdf, this.mv_class0_high_precision_cdf[i][j]);
            COPY(Sym1.kDefaultMvBitCdf, this.mv_bit_cdf[i][j]);
            COPY(Sym1.kDefaultMvFractionCdf, this.mv_fraction_cdf[i][j]);
            COPY(Sym1.kDefaultMvHighPrecisionCdf, this.mv_high_precision_cdf[i][j]);
         }
      }

      int quantizer_context = getQuantizerContext(base_quantizer_index);
      COPY(Sym1.kDefaultAllZeroCdf[quantizer_context], this.all_zero_cdf);
      COPY(Sym1.kDefaultEobPt16Cdf[quantizer_context], this.eob_pt_16_cdf);
      COPY(Sym1.kDefaultEobPt32Cdf[quantizer_context], this.eob_pt_32_cdf);
      COPY(Sym1.kDefaultEobPt64Cdf[quantizer_context], this.eob_pt_64_cdf);
      COPY(Sym1.kDefaultEobPt128Cdf[quantizer_context], this.eob_pt_128_cdf);
      COPY(Sym1.kDefaultEobPt256Cdf[quantizer_context], this.eob_pt_256_cdf);
      COPY(Sym1.kDefaultEobPt512Cdf[quantizer_context], this.eob_pt_512_cdf);
      COPY(Sym1.kDefaultEobPt1024Cdf[quantizer_context], this.eob_pt_1024_cdf);
      COPY(Sym1.kDefaultEobExtraCdf[quantizer_context], this.eob_extra_cdf);
      COPY(Sym1.kDefaultCoeffBaseEobCdf[quantizer_context], this.coeff_base_eob_cdf);
      COPY(Sym2.kDefaultCoeffBaseCdf[quantizer_context], this.coeff_base_cdf);
      COPY(Sym3.kDefaultCoeffBaseRangeCdf[quantizer_context], this.coeff_base_range_cdf);
      COPY(Sym1.kDefaultDcSignCdf[quantizer_context], this.dc_sign_cdf);
   }

   static int TxTypeIndex(int tx_set) {
      switch (tx_set) {
         case 1:
         case 3:
            return 0;
         case 2:
         case 4:
            return 1;
         case 5:
            return 2;
         default:
            return -1;
      }
   }

   static int getQuantizerContext(int base_quantizer_index) {
      if (base_quantizer_index <= 20) {
         return 0;
      } else if (base_quantizer_index <= 60) {
         return 1;
      } else {
         return base_quantizer_index <= 120 ? 2 : 3;
      }
   }

   static int PartitionCdfSize(int block_size_log2) {
      switch (block_size_log2) {
         case 1:
            return 4;
         case 5:
            return 8;
         default:
            return 10;
      }
   }

   static void COPY(int[] source, int[] destination) {
      Mem.cpy(destination, source, source.length);
   }

   static void COPY(int[][] source, int[][] destination) {
      for (int i = 0; i < source.length; i++) {
         for (int j = 0; j < source[i].length; j++) {
            destination[i][j] = source[i][j];
         }
      }
   }

   static void COPY(int[][][] source, int[][][] destination) {
      for (int i = 0; i < source.length; i++) {
         for (int j = 0; j < source[i].length; j++) {
            for (int k = 0; k < source[i][j].length; k++) {
               destination[i][j][k] = source[i][j][k];
            }
         }
      }
   }

   static void COPY(int[][][][] source, int[][][][] destination) {
      for (int i = 0; i < source.length; i++) {
         for (int j = 0; j < source[i].length; j++) {
            for (int k = 0; k < source[i][j].length; k++) {
               for (int l = 0; l < source[i][j][k].length; l++) {
                  destination[i][j][k][l] = source[i][j][k][l];
               }
            }
         }
      }
   }

   static void RESET(int[] source) {
      source[source.length - 1] = 0;
   }

   static void RESET(int[][] source) {
      for (int i = 0; i < source.length; i++) {
         source[i][source[i].length - 1] = 0;
      }
   }

   static void RESET(int[][][] source) {
      for (int i = 0; i < source.length; i++) {
         for (int j = 0; j < source[i].length; j++) {
            source[i][j][source[i][j].length - 1] = 0;
         }
      }
   }

   static void RESET(int[][][][] source) {
      for (int i = 0; i < source.length; i++) {
         for (int j = 0; j < source[i].length; j++) {
            for (int k = 0; k < source[i][j].length; k++) {
               source[i][j][k][source[i][j][k].length - 1] = 0;
            }
         }
      }
   }

   static void ResetPartitionCounters(Symbol context) {
      int block_size_log2 = D.k4x4WidthLog2[4];

      for (int[][] d1 : context.partition_cdf) {
         int cdf_size = PartitionCdfSize(block_size_log2++);

         for (int[] d2 : d1) {
            d2[cdf_size] = 0;
         }
      }
   }

   static void ResetPaletteColorIndexCounters(Symbol context) {
      for (int[][][] d1 : context.palette_color_index_cdf) {
         int cdf_size = 2;

         for (int[][] d2 : d1) {
            for (int[] d3 : d2) {
               d3[cdf_size] = 0;
            }

            cdf_size++;
         }
      }
   }

   static void ResetTxTypeCounters(Symbol context) {
      int set_index = 1;

      for (int[][][] d1 : context.intra_tx_type_cdf) {
         int cdf_size = D.kNumTransformTypesInSet[set_index++];

         for (int[][] d2 : d1) {
            for (int[] d3 : d2) {
               d3[cdf_size] = 0;
            }
         }
      }

      for (int[][] d1 : context.inter_tx_type_cdf) {
         int cdf_size = D.kNumTransformTypesInSet[set_index++];

         for (int[] d2 : d1) {
            d2[cdf_size] = 0;
         }
      }
   }

   static void ResetTxDepthCounters(Symbol context) {
      int delta = 1;

      for (int[][] d1 : context.tx_depth_cdf) {
         int cdf_size = 3 - delta;
         delta = 0;

         for (int[] d2 : d1) {
            d2[cdf_size] = 0;
         }
      }
   }

   static void ResetUVModeCounters(Symbol context) {
      int cdf_size = 13;

      for (int[][] d1 : context.uv_mode_cdf) {
         for (int[] d2 : d1) {
            d2[cdf_size] = 0;
         }

         cdf_size++;
      }
   }

   void ResetIntraFrameYModeCdf() {
      COPY(Sym1.kDefaultIntraFrameYModeCdf, this.intra_frame_y_mode_cdf);
   }

   void ResetCounters() {
      ResetPartitionCounters(this);
      RESET(this.segment_id_cdf);
      RESET(this.use_predicted_segment_id_cdf);
      RESET(this.skip_cdf);
      RESET(this.skip_mode_cdf);
      RESET(this.delta_q_cdf);
      RESET(this.delta_lf_cdf);
      RESET(this.delta_lf_multi_cdf);
      RESET(this.intra_block_copy_cdf);
      RESET(this.intra_frame_y_mode_cdf);
      RESET(this.y_mode_cdf);
      RESET(this.angle_delta_cdf);
      ResetUVModeCounters(this);
      RESET(this.cfl_alpha_signs_cdf);
      RESET(this.cfl_alpha_cdf);
      RESET(this.use_filter_intra_cdf);
      RESET(this.filter_intra_mode_cdf);
      ResetTxDepthCounters(this);
      RESET(this.tx_split_cdf);
      RESET(this.all_zero_cdf);
      ResetTxTypeCounters(this);
      RESET(this.eob_pt_16_cdf);
      RESET(this.eob_pt_32_cdf);
      RESET(this.eob_pt_64_cdf);
      RESET(this.eob_pt_128_cdf);
      RESET(this.eob_pt_256_cdf);
      RESET(this.eob_pt_512_cdf);
      RESET(this.eob_pt_1024_cdf);
      RESET(this.eob_extra_cdf);
      RESET(this.coeff_base_eob_cdf);
      RESET(this.coeff_base_cdf);
      RESET(this.coeff_base_range_cdf);
      RESET(this.dc_sign_cdf);
      RESET(this.restoration_type_cdf);
      RESET(this.use_wiener_cdf);
      RESET(this.use_sgrproj_cdf);
      RESET(this.has_palette_y_cdf);
      RESET(this.palette_y_size_cdf);
      RESET(this.has_palette_uv_cdf);
      RESET(this.palette_uv_size_cdf);
      ResetPaletteColorIndexCounters(this);
      RESET(this.is_inter_cdf);
      RESET(this.use_compound_reference_cdf);
      RESET(this.compound_reference_type_cdf);
      RESET(this.compound_reference_cdf);
      RESET(this.compound_backward_reference_cdf);
      RESET(this.single_reference_cdf);
      RESET(this.compound_prediction_mode_cdf);
      RESET(this.new_mv_cdf);
      RESET(this.zero_mv_cdf);
      RESET(this.reference_mv_cdf);
      RESET(this.ref_mv_index_cdf);
      RESET(this.is_inter_intra_cdf);
      RESET(this.inter_intra_mode_cdf);
      RESET(this.is_wedge_inter_intra_cdf);
      RESET(this.wedge_index_cdf);
      RESET(this.use_obmc_cdf);
      RESET(this.motion_mode_cdf);
      RESET(this.is_explicit_compound_type_cdf);
      RESET(this.is_compound_type_average_cdf);
      RESET(this.compound_type_cdf);
      RESET(this.interpolation_filter_cdf);
      RESET(this.mv_joint_cdf);
      RESET(this.mv_sign_cdf);
      RESET(this.mv_class_cdf);
      RESET(this.mv_class0_bit_cdf);
      RESET(this.mv_class0_fraction_cdf);
      RESET(this.mv_class0_high_precision_cdf);
      RESET(this.mv_bit_cdf);
      RESET(this.mv_fraction_cdf);
      RESET(this.mv_high_precision_cdf);
   }
}
