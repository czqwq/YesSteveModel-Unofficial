package rip.ysm.imagestream.avif.dec;

import java.util.Arrays;
import java.util.Stack;
import rip.ysm.imagestream.internal.LogWriter;

class Tile {
   final int number_;
   final int row_;
   final int column_;
   final byte[] data_;
   final int size_;
   final int row4x4_start_;
   final int row4x4_end_;
   final int column4x4_start_;
   final int column4x4_end_;
   final int superblock_rows_;
   final int superblock_columns_;
   boolean read_deltas_;
   final int[] subsampling_x_;
   final int[] subsampling_y_;
   final int[][][][] deblock_filter_levels_ = new int[8][4][8][2];
   int current_quantizer_index_;
   byte[][][] coefficient_levels_;
   byte[][][] dc_categories_;
   final D.Array2DView cdef_index_;
   final D.Array2DView cdef_skip_;
   final int[][] inter_transform_sizes_;
   final D.RefCountedBuffer current_frame_;
   final D.ObuSequenceHeader sequence_header_;
   final D.ObuFrameHeader frame_header_;
   final Entropy reader_;
   final Symbol symbol_decoder_context_;
   Symbol saved_symbol_decoder_context_;
   final D.SegmentationMap prev_segment_ids_;
   final PostFilter post_filter_;
   final D.BlockParamsHolder block_parameters_holder_;
   final Quant quantizer_;
   final int[][][][] quantizer_matrix_;
   final boolean split_parse_and_decode_;
   final int[] transform_types_ = new int[1024];
   boolean delta_lf_all_zero_;
   final boolean frame_parallel_;
   final boolean use_intra_prediction_buffer_;
   D.DynamicBufferBlockCdfContext top_context_ = new D.DynamicBufferBlockCdfContext();
   D.Array2DView[] buffer_ = new D.Array2DView[3];
   D.IntraPredictionBuffer[] intra_prediction_buffer_;
   final int[] residual_buffer_ = new int[4352];
   int residual_bufferPos_;
   D.ResidualBuffer[][] residual_buffer_threaded_;
   D.ResidualBufferPool residual_buffer_pool_;
   int residual_size_;
   int intra_block_copy_lag_;
   final int[] delta_lf_ = new int[4];
   int[] reference_frame_progress_cache_ = new int[8];
   D.TileScratchBufferPool tile_scratch_buffer_pool_ = new D.TileScratchBufferPool();
   D.RestorationUnitInfo[] reference_unit_info_ = new D.RestorationUnitInfo[3];
   D.BlockingCounterImpl pending_tiles_ = new D.BlockingCounterImpl(true);
   D.ThreadingParameters threading_ = new D.ThreadingParameters();
   D.BlockCdfContext left_context_ = new D.BlockCdfContext();
   D.RefCountedBuffer[] reference_frames_;
   D.TemporalMotionField motion_field_;
   boolean[] reference_frame_sign_bias_;
   int[][][] wedge_masks_;
   int[] reference_order_hint_;
   D.ThreadPool thread_pool_;
   D.PredictionParams prediction_parameters_;
   static final int[] kWarpValidThreshold = new int[]{16, 16, 16, 16, 16, 16, 32, 16, 16, 16, 32, 64, 32, 32, 32, 64, 64, 64, 64, 112, 112, 112};
   static D.BitMaskSet kPredictionModeNewMvMask = new D.BitMaskSet(17, 25, 22, 23, 20, 21);

   int SuperBlockRowIndex(int row4x4) {
      return row4x4 - this.row4x4_start_ >> (this.sequence_header_.use_128x128_superblock ? 5 : 4);
   }

   int SuperBlockColumnIndex(int column4x4) {
      return column4x4 - this.column4x4_start_ >> (this.sequence_header_.use_128x128_superblock ? 5 : 4);
   }

   int CdfContextIndex(int row_or_column4x4) {
      return row_or_column4x4 - (row_or_column4x4 & (this.sequence_header_.use_128x128_superblock ? -32 : -16));
   }

   int SuperBlockSize() {
      return this.sequence_header_.use_128x128_superblock ? 21 : 18;
   }

   int PlaneCount() {
      return this.sequence_header_.color_config.is_monochrome ? 1 : 3;
   }

   boolean IsRow4x4Inside(int row4x4) {
      return row4x4 >= this.row4x4_start_ && row4x4 < this.row4x4_end_;
   }

   boolean IsInside(int row4x4, int column4x4) {
      return this.IsRow4x4Inside(row4x4) && column4x4 >= this.column4x4_start_ && column4x4 < this.column4x4_end_;
   }

   boolean IsLeftInside(int column4x4) {
      return column4x4 > this.column4x4_start_;
   }

   boolean IsTopInside(int row4x4) {
      return row4x4 > this.row4x4_start_;
   }

   boolean IsTopLeftInside(int row4x4, int column4x4) {
      return row4x4 > this.row4x4_start_ && column4x4 > this.column4x4_start_;
   }

   boolean IsBottomRightInside(int row4x4, int column4x4) {
      return row4x4 < this.row4x4_end_ && column4x4 < this.column4x4_end_;
   }

   int BlockParametersStride() {
      return this.block_parameters_holder_.columns4x4();
   }

   boolean HasParameters(int row, int column) {
      return this.block_parameters_holder_.Find(row, column) != null;
   }

   D.BlockParams Parameters(int row, int column) {
      return this.block_parameters_holder_.Find(row, column);
   }

   int number() {
      return this.number_;
   }

   int superblock_rows() {
      return this.superblock_rows_;
   }

   int superblock_columns() {
      return this.superblock_columns_;
   }

   int row4x4_start() {
      return this.row4x4_start_;
   }

   int column4x4_start() {
      return this.column4x4_start_;
   }

   int column4x4_end() {
      return this.column4x4_end_;
   }

   D.ObuFrameHeader frame_header() {
      return this.frame_header_;
   }

   D.BlockParams BlockParametersAddress(int row4x4, int column4x4) {
      return this.block_parameters_holder_.Find(row4x4, column4x4);
   }

   D.BlockParams findGivenBlockParameters(int pos) {
      for (D.BlockParams bpx : this.block_parameters_holder_.block_parameters_) {
         if (bpx.pos == pos) {
            return bpx;
         }
      }

      return null;
   }

   static Tile Create(
      int tileNumber,
      byte[] data,
      int offset,
      int size,
      D.ObuSequenceHeader sequence_header,
      D.ObuFrameHeader frame_header,
      D.RefCountedBuffer currentFrame,
      D.DecoderState state,
      D.FrameScratchBuffer frameScratchBuffer,
      int[][][] wedge_masks,
      int[][][][] quantizerMatrix,
      Symbol savedSymbolDecoderContext,
      D.SegmentationMap prevSegmentIds,
      PostFilter postFilter,
      D.BlockingCounterImpl pendingTiles,
      boolean frameParallel,
      boolean useIntraPredictionBuffer
   ) {
      return new Tile(
         tileNumber,
         data,
         offset,
         size,
         sequence_header,
         frame_header,
         currentFrame,
         state,
         frameScratchBuffer,
         wedge_masks,
         quantizerMatrix,
         savedSymbolDecoderContext,
         prevSegmentIds,
         postFilter,
         pendingTiles,
         frameParallel,
         useIntraPredictionBuffer
      );
   }

   Tile(
      int tileNumber,
      byte[] data,
      int offset,
      int size,
      D.ObuSequenceHeader sequenceHeader,
      D.ObuFrameHeader frameHeader,
      D.RefCountedBuffer currentFrame,
      D.DecoderState state,
      D.FrameScratchBuffer frameScratchBuffer,
      int[][][] wedgeMasks,
      int[][][][] quantizerMatrix,
      Symbol savedSymbolDecoderContext,
      D.SegmentationMap prevSegmentIds,
      PostFilter postFilter,
      D.BlockingCounterImpl pendingTiles,
      boolean frameParallel,
      boolean useIntraPredictionBuffer
   ) {
      this.number_ = tileNumber;
      this.row_ = this.number_ / frameHeader.tile_info.tile_columns;
      this.column_ = this.number_ % frameHeader.tile_info.tile_columns;
      this.data_ = data;
      this.size_ = size;
      this.read_deltas_ = false;
      this.subsampling_x_ = new int[]{0, sequenceHeader.color_config.subsampling_x, sequenceHeader.color_config.subsampling_x};
      this.subsampling_y_ = new int[]{0, sequenceHeader.color_config.subsampling_y, sequenceHeader.color_config.subsampling_y};
      this.current_quantizer_index_ = frameHeader.quantizer.base_index;
      this.sequence_header_ = sequenceHeader;
      this.frame_header_ = frameHeader;
      this.reference_frame_sign_bias_ = state.reference_frame_sign_bias;
      this.reference_frames_ = state.reference_frame;
      this.motion_field_ = frameScratchBuffer.motion_field;
      this.reference_order_hint_ = state.reference_order_hint;
      this.wedge_masks_ = wedgeMasks;
      this.quantizer_matrix_ = quantizerMatrix;
      this.reader_ = new Entropy(this.data_, offset, this.size_, this.frame_header_.enable_cdf_update);
      this.symbol_decoder_context_ = frameScratchBuffer.symbol_decoder_context;
      this.saved_symbol_decoder_context_ = savedSymbolDecoderContext;
      this.prev_segment_ids_ = prevSegmentIds;
      this.post_filter_ = postFilter;
      this.block_parameters_holder_ = frameScratchBuffer.block_parameters_holder;
      this.quantizer_ = new Quant(this.sequence_header_.color_config.bitdepth, this.frame_header_.quantizer);
      this.residual_size_ = 1;
      this.intra_block_copy_lag_ = this.frame_header_.allow_intrabc ? (this.sequence_header_.use_128x128_superblock ? 3 : 5) : 1;
      this.current_frame_ = currentFrame;
      this.cdef_index_ = frameScratchBuffer.cdef_index;
      this.cdef_skip_ = frameScratchBuffer.cdef_skip;
      this.inter_transform_sizes_ = frameScratchBuffer.inter_transform_sizes;
      this.residual_buffer_pool_ = frameScratchBuffer.residual_buffer_pool;
      this.tile_scratch_buffer_pool_ = frameScratchBuffer.tile_scratch_buffer_pool;
      this.pending_tiles_ = pendingTiles;
      this.frame_parallel_ = frameParallel;
      this.use_intra_prediction_buffer_ = useIntraPredictionBuffer;
      this.intra_prediction_buffer_ = this.use_intra_prediction_buffer_ ? frameScratchBuffer.intra_prediction_buffers.get()[this.row_] : null;
      this.row4x4_start_ = frameHeader.tile_info.tile_row_start[this.row_];
      this.row4x4_end_ = frameHeader.tile_info.tile_row_start[this.row_ + 1];
      this.column4x4_start_ = frameHeader.tile_info.tile_column_start[this.column_];
      this.column4x4_end_ = frameHeader.tile_info.tile_column_start[this.column_ + 1];
      int block_width4x4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];
      int block_width4x4_log2 = D.k4x4HeightLog2[this.SuperBlockSize()];
      this.superblock_rows_ = this.row4x4_end_ - this.row4x4_start_ + block_width4x4 - 1 >> block_width4x4_log2;
      this.superblock_columns_ = this.column4x4_end_ - this.column4x4_start_ + block_width4x4 - 1 >> block_width4x4_log2;
      this.split_parse_and_decode_ = frameParallel;
      if (this.frame_parallel_) {
         Arrays.fill(this.reference_frame_progress_cache_, Integer.MIN_VALUE);
      }

      Arrays.fill(this.delta_lf_, 0);
      this.delta_lf_all_zero_ = true;
      Yuv buffer = this.post_filter_.frame_buffer();

      for (int plane = 0; plane < this.PlaneCount(); plane++) {
         int max_tx_length = plane == 0 ? 64 : 32;
         if (this.buffer_[plane] == null) {
            this.buffer_[plane] = new D.Array2DView();
         }

         this.buffer_[plane].Reset(D.Align(buffer.height(plane), max_tx_length), buffer.stride(plane), this.post_filter_.GetUnfilteredBuffer(plane));
      }

      int maxVal = Math.max(this.frame_header_.columns4x4, this.frame_header_.rows4x4);
      maxVal = Math.max(maxVal, 256);
      this.coefficient_levels_ = new byte[2][3][maxVal];
      this.dc_categories_ = new byte[2][3][maxVal];
      if (this.split_parse_and_decode_) {
         this.residual_buffer_threaded_ = new D.ResidualBuffer[this.superblock_rows_][this.superblock_columns_];

         for (int i = 0; i < this.superblock_rows_; i++) {
            for (int j = 0; j < this.superblock_columns_; j++) {
               this.residual_buffer_threaded_[i][j] = new D.ResidualBuffer();
            }
         }
      } else {
         this.prediction_parameters_ = new D.PredictionParams();
      }

      if (this.frame_header_.use_ref_frame_mvs) {
         LogWriter.writeLog("Tile init error : not supported mvs");
      }

      this.resetLoopRestorationParams();
      this.top_context_.Resize(this.superblock_columns_);
   }

   private static int getSinglePredictionMode(int index, int y_mode) {
      if (y_mode < 18) {
         return y_mode;
      } else {
         int lookup_index = y_mode - 18;
         return D.kCompoundToSinglePredictionMode[lookup_index][index];
      }
   }

   private static int getNumElements(int length, int start, int max) {
      return Math.min(length, max - start);
   }

   private static void setBlockValuesBoolean(int rows, int columns, boolean value, boolean[] dst, int dstPos, int stride) {
      switch (columns) {
         case 1:
            D.MemSetBlockBoolean(rows, 1, value, dst, dstPos, stride);
            break;
         case 2:
            D.MemSetBlockBoolean(rows, 2, value, dst, dstPos, stride);
            break;
         case 3:
         case 5:
         case 6:
         case 7:
         default:
            D.MemSetBlockBoolean(rows, 16, value, dst, dstPos, stride);
            break;
         case 4:
            D.MemSetBlockBoolean(rows, 4, value, dst, dstPos, stride);
            break;
         case 8:
            D.MemSetBlockBoolean(rows, 8, value, dst, dstPos, stride);
      }
   }

   private static void setBlockValues(int rows, int columns, int value, int[] dst, int dstPos, int stride) {
      switch (columns) {
         case 1:
            D.MemSetBlock(rows, 1, value, dst, dstPos, stride);
            break;
         case 2:
            D.MemSetBlock(rows, 2, value, dst, dstPos, stride);
            break;
         case 3:
         case 5:
         case 6:
         case 7:
         default:
            D.MemSetBlock(rows, 16, value, dst, dstPos, stride);
            break;
         case 4:
            D.MemSetBlock(rows, 4, value, dst, dstPos, stride);
            break;
         case 8:
            D.MemSetBlock(rows, 8, value, dst, dstPos, stride);
      }
   }

   private static void setTransformType(Block block, int x4, int y4, int w4, int h4, int tx_type, int[] transform_types) {
      int y_offset = y4 - block.row4x4;
      int x_offset = x4 - block.column4x4;
      int dstPos = y_offset * 32 + x_offset;
      setBlockValues(h4, w4, tx_type, transform_types, dstPos, 32);
   }

   private static void storeMotionFieldMvs(
      int referenceFrameToStore,
      D.MotionVector mvToStore,
      int stride,
      int rows,
      int columns,
      int[] referenceFrameRowStart,
      int rfrsPos,
      D.MotionVector[] mv,
      int mvPos
   ) {
      do {
         Mem.set(referenceFrameRowStart, rfrsPos, referenceFrameToStore, columns);
         Arrays.fill(mv, mvPos, mvPos + columns, mvToStore);
         rfrsPos += stride;
         mvPos += stride;
      } while (--rows != 0);
   }

   private static void moveCoefficientsForTxWidth64(int clamped_tx_height, int tx_width, int[] residual, int residualPos) {
      if (tx_width == 64) {
         int rows = clamped_tx_height - 2;
         int[] src = residual;
         int srcPos = residualPos + 32 * rows;
         residualPos += 64 * rows;
         int x = rows >> 1;

         do {
            Mem.cpy(residual, residualPos, src, srcPos, 32);
            Mem.cpy(residual, residualPos + 64, src, srcPos + 32, 32);
            Mem.set(src, srcPos + 32, 0, 32);
            srcPos -= 64;
            residualPos -= 128;
         } while (--x != 0);

         Mem.cpy(residual, residualPos + 64, src, srcPos + 32, 32);
         Mem.set(src, srcPos + 32, 0, 32);
      }
   }

   private static void getClampParameters(Block block, int[] min, int[] max) {
      int kMvBorder4x4 = 4;
      int row_border = 4 + block.height4x4;
      int column_border = 4 + block.width4x4;
      int macroblocks_to_top_edge = -block.row4x4;
      int macroblocks_to_bottom_edge = block.tile.frame_header().rows4x4 - block.height4x4 - block.row4x4;
      int macroblocks_to_left_edge = -block.column4x4;
      int macroblocks_to_right_edge = block.tile.frame_header().columns4x4 - block.width4x4 - block.column4x4;
      min[0] = (macroblocks_to_top_edge - row_border) * 32;
      min[1] = (macroblocks_to_left_edge - column_border) * 32;
      max[0] = (macroblocks_to_bottom_edge + row_border) * 32;
      max[1] = (macroblocks_to_right_edge + column_border) * 32;
   }

   private static int getCoeffBaseContextEob(int txSize, int index) {
      if (index == 0) {
         return 0;
      } else {
         int adjusted_tx_size = D.kAdjustedTransformSize[txSize];
         int tx_width_log2 = D.kTransformWidthLog2[adjusted_tx_size];
         int tx_height = D.kTransformHeight[adjusted_tx_size];
         if (index <= (tx_height << tx_width_log2) / 8) {
            return 1;
         } else {
            return index <= (tx_height << tx_width_log2) / 4 ? 2 : 3;
         }
      }
   }

   private static int getCoeffBaseRangeContextEob(int adjusted_tx_width_log2, int pos, int tx_class) {
      if (pos == 0) {
         return 0;
      } else {
         int tx_width = 1 << adjusted_tx_width_log2;
         int row = pos >> adjusted_tx_width_log2;
         int column = pos & tx_width - 1;
         return 14 >> ((tx_class == 0 & (row | column) < 2 ? 1 : 0) | tx_class & (column == 0 ? 1 : 0) | tx_class >> 1 & (row == 0 ? 1 : 0));
      }
   }

   boolean processSuperBlockRow(int row4x4, D.TileScratchBuffer scratch_buffer, int processing_mode, boolean save_symbol_decoder_context) {
      if (row4x4 >= this.row4x4_start_ && row4x4 < this.row4x4_end_) {
         if (scratch_buffer == null) {
            LogWriter.writeLog("Scratch buffer is null");
         }

         int block_width4x4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];

         for (int column4x4 = this.column4x4_start_; column4x4 < this.column4x4_end_; column4x4 += block_width4x4) {
            if (!this.processSuperBlock(row4x4, column4x4, scratch_buffer, processing_mode)) {
               LogWriter.writeLog("process superblock error");
               return false;
            }
         }

         if (save_symbol_decoder_context && row4x4 + block_width4x4 >= this.row4x4_end_) {
            this.saveSymbolDecoderContext();
         }

         if (processing_mode == 1 || processing_mode == 2) {
            this.populateIntraPredictionBuffer(row4x4);
         }

         return true;
      } else {
         return true;
      }
   }

   private void saveSymbolDecoderContext() {
      if (this.frame_header_.enable_frame_end_update_cdf && this.number_ == this.frame_header_.tile_info.context_update_id) {
         this.saved_symbol_decoder_context_ = this.symbol_decoder_context_;
      }
   }

   boolean parseAndDecode() {
      if (this.split_parse_and_decode_) {
         if (!this.threadedParseAndDecode()) {
            return false;
         } else {
            this.saveSymbolDecoderContext();
            return true;
         }
      } else {
         D.TileScratchBuffer scratch_buffer = this.tile_scratch_buffer_pool_.get();
         if (scratch_buffer == null) {
            LogWriter.writeLog("Parse And Decode Error : null scratch buffer");
            return false;
         } else {
            int block_width4x4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];

            for (int row4x4 = this.row4x4_start_; row4x4 < this.row4x4_end_; row4x4 += block_width4x4) {
               if (!this.processSuperBlockRow(row4x4, scratch_buffer, 2, true)) {
                  this.pending_tiles_.Decrement(false);
                  LogWriter.writeLog("Parse And Decode Error : process super block row");
                  return false;
               }
            }

            this.tile_scratch_buffer_pool_.release(scratch_buffer);
            this.pending_tiles_.Decrement(true);
            return true;
         }
      }
   }

   boolean parse() {
      int block_width4x4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];
      D.TileScratchBuffer scratch_buffer = this.tile_scratch_buffer_pool_.get();

      for (int row4x4 = this.row4x4_start_; row4x4 < this.row4x4_end_; row4x4 += block_width4x4) {
         if (!this.processSuperBlockRow(row4x4, scratch_buffer, 0, false)) {
            LogWriter.writeLog("Parse error: process super block");
            return false;
         }
      }

      this.tile_scratch_buffer_pool_.release(scratch_buffer);
      this.saveSymbolDecoderContext();
      return true;
   }

   boolean decode(int[] superblock_row_progress, int[] superblock_row_progress_condvar) {
      int block_width4x4 = this.sequence_header_.use_128x128_superblock ? 32 : 16;
      int block_width4x4_log2 = this.sequence_header_.use_128x128_superblock ? 5 : 4;
      D.TileScratchBuffer scratch_buffer = this.tile_scratch_buffer_pool_.get();
      if (scratch_buffer == null) {
         LogWriter.writeLog("Tile Error: Scratch buffer is null");
         return false;
      } else {
         int row4x4 = this.row4x4_start_;

         for (int index = this.row4x4_start_ >> block_width4x4_log2; row4x4 < this.row4x4_end_; index++) {
            if (!this.processSuperBlockRow(row4x4, scratch_buffer, 1, false)) {
               LogWriter.writeLog("Tile Error: Process super block row");
               return false;
            }

            if (this.post_filter_.DoDeblock()) {
               this.post_filter_.ApplyDeblockFilter(0, row4x4, this.column4x4_start_ + 16, this.column4x4_end_, block_width4x4);
               if (row4x4 != this.row4x4_start_) {
                  this.post_filter_.ApplyDeblockFilter(1, row4x4, this.column4x4_start_ + 16, this.column4x4_end_ - 16, block_width4x4);
               }
            }

            boolean notify = ++superblock_row_progress[index] == this.frame_header_.tile_info.tile_columns;
            if (notify) {
            }

            row4x4 += block_width4x4;
         }

         return true;
      }
   }

   boolean threadedParseAndDecode() {
      this.threading_.sb_state = new int[this.superblock_rows_][this.superblock_columns_];
      this.threading_.pending_jobs++;
      int block_width4x4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];
      D.TileScratchBuffer scratch_buffer = this.tile_scratch_buffer_pool_.get();
      int row4x4 = this.row4x4_start_;

      for (int row_index = 0; row4x4 < this.row4x4_end_; row_index++) {
         int column4x4 = this.column4x4_start_;

         for (int column_index = 0; column4x4 < this.column4x4_end_; column_index++) {
            this.processSuperBlock(row4x4, column4x4, scratch_buffer, 0);
            this.threading_.sb_state[row_index][column_index] = 1;
            if (this.canDecode(row_index, column_index)) {
               this.threading_.pending_jobs++;
               this.threading_.sb_state[row_index][column_index] = 2;
               this.decodeSuperBlock(row_index, column_index, block_width4x4);
            }

            column4x4 += block_width4x4;
         }

         row4x4 += block_width4x4;
      }

      return true;
   }

   private boolean canDecode(int rowIndex, int columnIndex) {
      if (rowIndex >= this.superblock_rows_ || columnIndex >= this.superblock_columns_ || this.threading_.sb_state[rowIndex][columnIndex] != 1) {
         return false;
      } else if (rowIndex == 0 && columnIndex == 0) {
         return true;
      } else if (rowIndex == 0) {
         return this.threading_.sb_state[0][columnIndex - 1] == 3;
      } else {
         int top_right_column_index = Math.min(columnIndex + this.intra_block_copy_lag_, this.superblock_columns_ - 1);
         return this.threading_.sb_state[rowIndex - 1][top_right_column_index] == 3
            && (columnIndex == 0 || this.threading_.sb_state[rowIndex][columnIndex - 1] == 3);
      }
   }

   private void decodeSuperBlock(int row_index, int column_index, int block_width4x4) {
      int row4x4 = this.row4x4_start_ + row_index * block_width4x4;
      int column4x4 = this.column4x4_start_ + column_index * block_width4x4;
      D.TileScratchBuffer scratch_buffer = this.tile_scratch_buffer_pool_.get();
      boolean ok = scratch_buffer != null;
      if (ok) {
         ok = this.processSuperBlock(row4x4, column4x4, scratch_buffer, 1);
         this.tile_scratch_buffer_pool_.release(scratch_buffer);
      }

      if (ok) {
         this.threading_.sb_state[row_index][column_index] = 3;
         int[] candidateRowIndices = new int[]{row_index + 1, row_index};
         int[] candidateColumnIndices = new int[]{Math.max(0, column_index - this.intra_block_copy_lag_), column_index + 1};

         for (int i = 0; i < candidateRowIndices.length; i++) {
            int candidate_row_index = candidateRowIndices[i];
            int candidate_column_index = candidateColumnIndices[i];
            if (this.canDecode(candidate_row_index, candidate_column_index)) {
               this.threading_.pending_jobs++;
               this.threading_.sb_state[candidate_row_index][candidate_column_index] = 2;
               this.decodeSuperBlock(candidate_row_index, candidate_column_index, block_width4x4);
            }
         }
      }
   }

   private void populateIntraPredictionBuffer(int row4x4) {
      int blockWidth4X4 = D.kNum4x4BlocksWide[this.SuperBlockSize()];
      if (this.use_intra_prediction_buffer_ && row4x4 + blockWidth4X4 < this.row4x4_end_) {
         int pixelSize = 1;

         for (int plane = 0; plane < this.PlaneCount(); plane++) {
            int row_to_copy = ((row4x4 + blockWidth4X4) * 4 >> this.subsampling_y_[plane]) - 1;
            int pixels_to_copy = ((this.column4x4_end_ - this.column4x4_start_) * 4 >> this.subsampling_x_[plane]) * pixelSize;
            int column_start = this.column4x4_start_ * 4 >> this.subsampling_x_[plane];
            int[] startBuffer = this.buffer_[plane].data_;
            int startBufferPos = this.buffer_[plane].columns() * row_to_copy + column_start;
            int[] intraBuffer = this.intra_prediction_buffer_[plane].get();
            int intraBufferPos = column_start * pixelSize;
            Mem.cpy(intraBuffer, intraBufferPos, startBuffer, startBufferPos, pixels_to_copy);
         }
      }
   }

   private int getTransformAllZeroContext(Block block, int plane, int txSize, int x4, int y4, int w4, int h4) {
      int max_x4x4 = this.frame_header_.columns4x4 >> this.subsampling_x_[plane];
      int max_y4x4 = this.frame_header_.rows4x4 >> this.subsampling_y_[plane];
      int txWidth = D.kTransformWidth[txSize];
      int txHeight = D.kTransformHeight[txSize];
      int planeSize = block.residual_size[plane];
      int blockWidth = D.kBlockWidthPixels[planeSize];
      int blockHeight = D.kBlockHeightPixels[planeSize];
      int top = 0;
      int left = 0;
      int num_top_elements = getNumElements(w4, x4, max_x4x4);
      int num_left_elements = getNumElements(h4, y4, max_y4x4);
      if (plane == 0) {
         if (blockWidth == txWidth && blockHeight == txHeight) {
            return 0;
         } else {
            byte[] coefficient_levels = this.coefficient_levels_[1][plane];
            int coefPos = x4;

            for (int i = 0; i < num_top_elements; i++) {
               top = Math.max(top, coefficient_levels[i + coefPos] & 255);
            }

            coefficient_levels = this.coefficient_levels_[0][plane];
            coefPos = y4;

            for (int i = 0; i < num_left_elements; i++) {
               left = Math.max(left, coefficient_levels[i + coefPos] & 255);
            }

            return D.kAllZeroContextsByTopLeft[top][left];
         }
      } else {
         byte[] coefficient_levels = this.coefficient_levels_[1][plane];
         int coefPos = x4;
         byte[] dc_categories = this.dc_categories_[1][plane];
         int dcPos = x4;

         for (int i = 0; i < num_top_elements; i++) {
            top |= coefficient_levels[coefPos + i] & 255;
            top |= dc_categories[dcPos + i];
         }

         coefficient_levels = this.coefficient_levels_[0][plane];
         coefPos = y4;
         dc_categories = this.dc_categories_[0][plane];
         dcPos = y4;

         for (int i = 0; i < num_left_elements; i++) {
            left |= coefficient_levels[coefPos + i] & 255;
            left |= dc_categories[dcPos + i];
         }

         return (top != 0 ? 1 : 0) + (left != 0 ? 1 : 0) + 7 + 3 * (blockWidth * blockHeight > txWidth * txHeight ? 1 : 0);
      }
   }

   private int getTransformSet(int txSize, boolean isInter) {
      int tx_size_square_min = D.kTransformSizeSquareMin[txSize];
      int tx_size_square_max = D.kTransformSizeSquareMax[txSize];
      if (tx_size_square_max == 18) {
         return 0;
      } else if (isInter) {
         if (this.frame_header_.reduced_tx_set || tx_size_square_max == 14) {
            return 5;
         } else {
            return tx_size_square_min == 9 ? 4 : 3;
         }
      } else if (tx_size_square_max == 14) {
         return 0;
      } else {
         return !this.frame_header_.reduced_tx_set && tx_size_square_min != 9 ? 1 : 2;
      }
   }

   private int computeTransformType(Block block, int plane, int txSize, int blockX, int blockY) {
      D.BlockParams bp = block.bp;
      int tx_size_square_max = D.kTransformSizeSquareMax[txSize];
      if (this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id] || tx_size_square_max == 18) {
         return 0;
      } else if (plane == 0) {
         return this.transform_types_[(blockY - block.row4x4) * 32 + (blockX - block.column4x4)];
      } else {
         int tx_set = this.getTransformSet(txSize, bp.is_inter);
         int tx_type;
         if (bp.is_inter) {
            int x4 = Math.max(block.column4x4, blockX << this.subsampling_x_[1]);
            int y4 = Math.max(block.row4x4, blockY << this.subsampling_y_[1]);
            tx_type = this.transform_types_[(y4 - block.row4x4) * 32 + (x4 - block.column4x4)];
         } else {
            tx_type = D.kModeToTransformType[bp.prediction_parameters.uv_mode];
         }

         return D.kTransformTypeInSetMask[tx_set].Contains(tx_type) ? tx_type : 0;
      }
   }

   private void readTransformType(Block block, int x4, int y4, int tx_size) {
      D.BlockParams bp = block.bp;
      int tx_set = this.getTransformSet(tx_size, bp.is_inter);
      int tx_type = 0;
      if (tx_set != 0 && this.frame_header_.segmentation.qindex[bp.prediction_parameters.segment_id] > 0) {
         int cdf_index = Symbol.TxTypeIndex(tx_set);
         int cdf_tx_size_index = D.TransformSizeToSquareTransformIndex(D.kTransformSizeSquareMin[tx_size]);
         if (bp.is_inter) {
            int[] cdf = this.symbol_decoder_context_.inter_tx_type_cdf[cdf_index][cdf_tx_size_index];
            switch (tx_set) {
               case 3:
                  tx_type = this.reader_.readSymbol(cdf, 16);
                  break;
               case 4:
                  tx_type = this.reader_.readSymbol(cdf, 12);
                  break;
               default:
                  tx_type = this.reader_.readSymbol(cdf) ? 1 : 0;
            }
         } else {
            int intra_direction = block.bp.prediction_parameters.use_filter_intra
               ? D.kFilterIntraModeToIntraPredictor[block.bp.prediction_parameters.filter_intra_mode]
               : bp.y_mode;
            int[] cdf = this.symbol_decoder_context_.intra_tx_type_cdf[cdf_index][cdf_tx_size_index][intra_direction];
            tx_type = tx_set == 1 ? this.reader_.readSymbol(cdf, 7) : this.reader_.readSymbol(cdf, 5);
         }

         tx_type = D.kInverseTransformTypeBySet[tx_set - 1][tx_type];
      }

      setTransformType(block, x4, y4, D.kTransformWidth4x4[tx_size], D.kTransformHeight4x4[tx_size], tx_type, this.transform_types_);
   }

   private void readCoeffBase2D(
      int[] scan,
      int tx_size,
      int adjustedTxWidthLog2,
      int eob,
      int[][] coeffBaseCdf,
      int[][] coeff_base_range_cdf,
      int[] quantizedBuffer,
      int qPos,
      int[] levelBuffer
   ) {
      int tx_width = 1 << adjustedTxWidthLog2;

      for (int i = eob - 2; i >= 1; i--) {
         int pos = scan[i] & 65535;
         int row = pos >> adjustedTxWidthLog2;
         int column = pos & tx_width - 1;
         int neighbor_sum = 1
            + levelBuffer[pos + 1]
            + levelBuffer[pos + tx_width]
            + levelBuffer[pos + tx_width + 1]
            + levelBuffer[pos + 2]
            + levelBuffer[pos + tx_width * 2];
         int context = (neighbor_sum > 7 ? 4 : neighbor_sum / 2) + D.kCoeffBaseContextOffset[tx_size][Math.min(row, 4)][Math.min(column, 4)];
         int level = this.reader_.readSymbol(coeffBaseCdf[context], 4);
         levelBuffer[pos] = level & 0xFF;
         if (level > 2) {
            int contextx = Math.min(
               6, (1 + quantizedBuffer[qPos + pos + 1] + quantizedBuffer[qPos + pos + tx_width] + quantizedBuffer[qPos + pos + tx_width + 1]) / 2
            );
            contextx += 14 >> ((row | column) < 2 ? 1 : 0);
            level += this.readCoeffBaseRange(coeff_base_range_cdf[contextx]);
         }

         quantizedBuffer[qPos + pos] = level;
      }

      int level = this.reader_.readSymbol(coeffBaseCdf[0], 4);
      levelBuffer[0] = level & 0xFF;
      if (level > 2) {
         int context = Math.min(6, (1 + quantizedBuffer[qPos + 1] + quantizedBuffer[qPos + tx_width] + quantizedBuffer[qPos + tx_width + 1]) / 2);
         level += this.readCoeffBaseRange(coeff_base_range_cdf[context]);
      }

      quantizedBuffer[qPos] = level;
   }

   private void readCoeffBaseHorizontal(
      int[] scan,
      int TransformSize,
      int adjusted_tx_width_log2,
      int eob,
      int[][] coeff_base_cdf,
      int[][] coeff_base_range_cdf,
      int[] quantized_buffer,
      int qPos,
      int[] level_buffer
   ) {
      int tx_width = 1 << adjusted_tx_width_log2;
      int i = eob - 2;

      do {
         int pos = scan[i] & 65535;
         int column = pos & tx_width - 1;
         int neighbor_sum = 1
            + level_buffer[pos + 1]
            + level_buffer[pos + tx_width]
            + level_buffer[pos + 2]
            + level_buffer[pos + 3]
            + (column + 4 < tx_width ? level_buffer[pos + 4] : 0);
         int context = (neighbor_sum > 7 ? 4 : neighbor_sum / 2) + D.kCoeffBasePositionContextOffset[column];
         int level = this.reader_.readSymbol(coeff_base_cdf[context], 4);
         level_buffer[pos] = level & 0xFF;
         if (level > 2) {
            int contextx = Math.min(6, (1 + quantized_buffer[qPos + pos + 1] + quantized_buffer[qPos + pos + tx_width] + quantized_buffer[qPos + pos + 2]) / 2);
            if (pos != 0) {
               contextx += 14 >> (column == 0 ? 1 : 0);
            }

            level += this.readCoeffBaseRange(coeff_base_range_cdf[contextx]);
         }

         quantized_buffer[qPos + pos] = level;
      } while (--i >= 0);
   }

   private void readCoeffBaseVertical(
      int[] scan,
      int TransformSize,
      int adjusted_tx_width_log2,
      int eob,
      int[][] coeff_base_cdf,
      int[][] coeff_base_range_cdf,
      int[] quantized_buffer,
      int qPos,
      int[] level_buffer
   ) {
      int tx_width = 1 << adjusted_tx_width_log2;
      int i = eob - 2;

      do {
         int pos = scan[i] & 65535;
         int row = pos >> adjusted_tx_width_log2;
         int column = pos & tx_width - 1;
         int neighbor_sum = 1
            + (column + 1 < tx_width ? level_buffer[pos + 1] : 0)
            + level_buffer[pos + tx_width]
            + level_buffer[pos + tx_width * 2]
            + level_buffer[pos + tx_width * 3]
            + level_buffer[pos + tx_width * 4];
         int context = (neighbor_sum > 7 ? 4 : neighbor_sum / 2) + D.kCoeffBasePositionContextOffset[row];
         int level = this.reader_.readSymbol(coeff_base_cdf[context], 4);
         level_buffer[pos] = level & 0xFF;
         if (level > 2) {
            int quantized_column1 = column + 1 < tx_width ? quantized_buffer[qPos + pos + 1] : 0;
            int contextx = Math.min(6, (1 + quantized_column1 + quantized_buffer[qPos + pos + tx_width] + quantized_buffer[qPos + pos + tx_width * 2]) / 2);
            if (pos != 0) {
               contextx += 14 >> (row == 0 ? 1 : 0);
            }

            level += this.readCoeffBaseRange(coeff_base_range_cdf[contextx]);
         }

         quantized_buffer[qPos + pos] = level;
      } while (--i >= 0);
   }

   private static int accumulateByte(byte[] arr, int start, int end, int initial) {
      int fullEnd = start + end;

      for (int i = start; i < fullEnd; i++) {
         initial += arr[i];
      }

      return initial;
   }

   private int getDcSignContext(int x4, int y4, int w4, int h4, int plane) {
      int max_x4x4 = this.frame_header_.columns4x4 >> this.subsampling_x_[plane];
      byte[] dc_categories = this.dc_categories_[1][plane];
      int dc_sign = accumulateByte(dc_categories, x4, getNumElements(w4, x4, max_x4x4), 0);
      int max_y4x4 = this.frame_header_.rows4x4 >> this.subsampling_y_[plane];
      dc_categories = this.dc_categories_[0][plane];
      dc_sign = accumulateByte(dc_categories, y4, getNumElements(h4, y4, max_y4x4), dc_sign);
      return (dc_sign < 0 ? 1 : 0) + (dc_sign > 0 ? 1 : 0) * 2;
   }

   private void setEntropyContexts(int x4, int y4, int w4, int h4, int plane, int coefficient_level, int dc_category) {
      int max_x4x4 = this.frame_header_.columns4x4 >> this.subsampling_x_[plane];
      int num_top_elements = getNumElements(w4, x4, max_x4x4);
      Mem.set(this.coefficient_levels_[1][plane], x4, (byte)coefficient_level, num_top_elements);
      Mem.set(this.dc_categories_[1][plane], x4, (byte)dc_category, num_top_elements);
      int max_y4x4 = this.frame_header_.rows4x4 >> this.subsampling_y_[plane];
      int num_left_elements = getNumElements(h4, y4, max_y4x4);
      Mem.set(this.coefficient_levels_[0][plane], y4, (byte)coefficient_level, num_left_elements);
      Mem.set(this.dc_categories_[0][plane], y4, (byte)dc_category, num_left_elements);
   }

   boolean readSignAndApplyDequantization(
      int[] scan,
      int i,
      int q_value,
      int[] quantizer_matrix,
      int shift,
      int max_value,
      int[] dc_sign_cdf,
      int[] dc_category,
      int[] coefficient_level,
      int[] residual_buffer,
      int residual_bufferPos,
      boolean is_dc_coefficient
   ) {
      int pos = is_dc_coefficient ? 0 : scan[i];
      int level = residual_buffer[residual_bufferPos + pos];
      if (level == 0) {
         return true;
      } else {
         int sign = is_dc_coefficient ? (this.reader_.readSymbol(dc_sign_cdf) ? 1 : 0) : this.reader_.readBit();
         if (level > 14) {
            int length = 0;
            boolean golomb_length_bit = false;

            do {
               golomb_length_bit = this.reader_.readBit() != 0;
               if (++length > 20) {
                  LogWriter.writeLog("ReadSignAndApplyDequantization error: invalid golomb" + length);
                  return false;
               }
            } while (!golomb_length_bit);

            int x = 1;

            for (int ii = length - 2; ii >= 0; ii--) {
               x = x << 1 | this.reader_.readBit();
            }

            level += x - 1;
         }

         if (is_dc_coefficient) {
            dc_category[0] = sign != 0 ? -1 : 1;
         }

         level &= 1048575;
         coefficient_level[0] += level;
         int q = q_value;
         if (quantizer_matrix != null) {
            q = D.RightShiftWithRounding(q_value * quantizer_matrix[pos], 5);
         }

         int dequantized_value = (int)((long)q * level & 16777215L);
         dequantized_value >>= shift;
         dequantized_value = Math.min(dequantized_value - sign, max_value) ^ -sign;
         residual_buffer[residual_bufferPos + pos] = dequantized_value;
         return true;
      }
   }

   private int readCoeffBaseRange(int[] cdf) {
      int level = 0;

      for (int j = 0; j < 4; j++) {
         int coeff_base_range = this.reader_.readSymbol(cdf, 4);
         level += coeff_base_range;
         if (coeff_base_range < 3) {
            break;
         }
      }

      return level;
   }

   private int readTransformCoefficients(Block block, int plane, int start_x, int start_y, int tx_size, int[] tx_type) {
      int x4 = start_x / 4;
      int y4 = start_y / 4;
      int w4 = D.kTransformWidth4x4[tx_size];
      int h4 = D.kTransformHeight4x4[tx_size];
      int tx_size_context = D.kTransformSizeContext[tx_size];
      int context = this.getTransformAllZeroContext(block, plane, tx_size, x4, y4, w4, h4);
      boolean all_zero = this.reader_.readSymbol(this.symbol_decoder_context_.all_zero_cdf[tx_size_context][context]);
      if (all_zero) {
         if (plane == 0) {
            setTransformType(block, x4, y4, w4, h4, 0, this.transform_types_);
         }

         this.setEntropyContexts(x4, y4, w4, h4, plane, 0, 0);
         tx_type[0] = 16;
         return 0;
      } else {
         int tx_width = D.kTransformWidth[tx_size];
         int tx_height = D.kTransformHeight[tx_size];
         int adjusted_tx_size = D.kAdjustedTransformSize[tx_size];
         int adjusted_tx_width_log2 = D.kTransformWidthLog2[adjusted_tx_size];
         int tx_padding = (1 << adjusted_tx_width_log2) * 4;
         int[] residual = block.residual;
         int residualPos = block.residualPos;
         Arrays.fill(residual, 0);
         int[] level_buffer = new int[1152];
         Mem.set(level_buffer, 0, D.kTransformWidth[adjusted_tx_size] * D.kTransformHeight[adjusted_tx_size] + tx_padding);
         int clamped_tx_height = Math.min(tx_height, 32);
         if (plane == 0) {
            this.readTransformType(block, x4, y4, tx_size);
         }

         D.BlockParams bp = block.bp;
         tx_type[0] = this.computeTransformType(block, plane, tx_size, x4, y4);
         int eob_multi_size = D.kEobMultiSizeLookup[tx_size];
         int plane_type = D.GetPlaneType(plane);
         int tx_class = Reconstruct.getTransformClass(tx_type[0]);
         context = tx_class != 0 ? 1 : 0;
         int eob_pt = 1;

         eob_pt = switch (eob_multi_size) {
            case 0 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_16_cdf[plane_type][context], 5);
            case 1 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_32_cdf[plane_type][context], 6);
            case 2 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_64_cdf[plane_type][context], 7);
            case 3 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_128_cdf[plane_type][context], 8);
            case 4 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_256_cdf[plane_type][context], 9);
            case 5 -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_512_cdf[plane_type], 10);
            default -> eob_pt + this.reader_.readSymbol(this.symbol_decoder_context_.eob_pt_1024_cdf[plane_type], 11);
         };
         int eob = eob_pt < 2 ? eob_pt : (1 << eob_pt - 2) + 1;
         if (eob_pt >= 3) {
            context = eob_pt - 3;
            boolean eob_extra = this.reader_.readSymbol(this.symbol_decoder_context_.eob_extra_cdf[tx_size_context][plane_type][context]);
            if (eob_extra) {
               eob += 1 << eob_pt - 3;
            }

            for (int i = 1; i < eob_pt - 2; i++) {
               if (this.reader_.readBit() != 0) {
                  eob += 1 << eob_pt - i - 3;
               }
            }
         }

         int[] scan = Scan.SCANTABLE[tx_class][tx_size];
         int clamped_tx_size_context = Math.min(tx_size_context, 3);
         int[][] coeff_base_range_cdf = this.symbol_decoder_context_.coeff_base_range_cdf[clamped_tx_size_context][plane_type];
         context = getCoeffBaseContextEob(tx_size, eob - 1);
         int pos = scan[eob - 1] & 65535;
         int level = 1 + this.reader_.readSymbol(this.symbol_decoder_context_.coeff_base_eob_cdf[tx_size_context][plane_type][context], 3);
         level_buffer[pos] = level;
         if (level > 2) {
            level += this.readCoeffBaseRange(coeff_base_range_cdf[getCoeffBaseRangeContextEob(adjusted_tx_width_log2, pos, tx_class)]);
         }

         residual[residualPos + pos] = level;
         if (eob > 1) {
            switch (tx_class) {
               case 0:
                  this.readCoeffBase2D(
                     scan,
                     tx_size,
                     adjusted_tx_width_log2,
                     eob,
                     this.symbol_decoder_context_.coeff_base_cdf[tx_size_context][plane_type],
                     coeff_base_range_cdf,
                     residual,
                     residualPos,
                     level_buffer
                  );
                  break;
               case 1:
                  this.readCoeffBaseHorizontal(
                     scan,
                     tx_size,
                     adjusted_tx_width_log2,
                     eob,
                     this.symbol_decoder_context_.coeff_base_cdf[tx_size_context][plane_type],
                     coeff_base_range_cdf,
                     residual,
                     residualPos,
                     level_buffer
                  );
                  break;
               case 2:
                  this.readCoeffBaseVertical(
                     scan,
                     tx_size,
                     adjusted_tx_width_log2,
                     eob,
                     this.symbol_decoder_context_.coeff_base_cdf[tx_size_context][plane_type],
                     coeff_base_range_cdf,
                     residual,
                     residualPos,
                     level_buffer
                  );
            }
         }

         pos = (1 << 7 + this.sequence_header_.color_config.bitdepth) - 1;
         level = Quant.GetQIndex(this.frame_header_.segmentation, bp.prediction_parameters.segment_id, this.current_quantizer_index_);
         int dc_q_value = this.quantizer_.GetDcValue(plane, level);
         int ac_q_value = this.quantizer_.GetAcValue(plane, level);
         int shift = D.kQuantizationShift[tx_size];
         int[] quantizer_matrix = this.frame_header_.quantizer.use_matrix
               && tx_type[0] < 9
               && !this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id]
               && this.frame_header_.quantizer.matrix_level[plane] < 15
            ? this.quantizer_matrix_[this.frame_header_.quantizer.matrix_level[plane]][plane_type][adjusted_tx_size]
            : null;
         int[] coefficient_level = new int[]{0};
         int[] dc_category = new int[]{0};
         int[] dc_sign_cdf = residual[residualPos + 0] != 0
            ? this.symbol_decoder_context_.dc_sign_cdf[plane_type][this.getDcSignContext(x4, y4, w4, h4, plane)]
            : null;
         if (!this.readSignAndApplyDequantization(
            scan, 0, dc_q_value, quantizer_matrix, shift, pos, dc_sign_cdf, dc_category, coefficient_level, residual, residualPos, true
         )) {
            LogWriter.writeLog("ReadTransformCoefficients : error1");
            return -1;
         } else {
            if (eob > 1) {
               int ix = 1;

               do {
                  if (!this.readSignAndApplyDequantization(
                     scan, ix, ac_q_value, quantizer_matrix, shift, pos, null, null, coefficient_level, residual, residualPos, false
                  )) {
                     LogWriter.writeLog("ReadTransformCoefficients : error2");
                     return -1;
                  }
               } while (++ix < eob);

               moveCoefficientsForTxWidth64(clamped_tx_height, tx_width, residual, residualPos);
            }

            this.setEntropyContexts(x4, y4, w4, h4, plane, Math.min(4, coefficient_level[0]), dc_category[0]);
            if (this.split_parse_and_decode_) {
               block.residualPos = block.residualPos + tx_width * tx_height * this.residual_size_;
            }

            return eob;
         }
      }
   }

   private boolean transformBlock(Block block, int plane, int baseX, int baseY, int txSize, int x, int y, int mode) {
      D.BlockParams bp = block.bp;
      int subsampling_x = this.subsampling_x_[plane];
      int subsampling_y = this.subsampling_y_[plane];
      int start_x = baseX + x * 4;
      int start_y = baseY + y * 4;
      int max_x = this.frame_header_.columns4x4 * 4 >> subsampling_x;
      int max_y = this.frame_header_.rows4x4 * 4 >> subsampling_y;
      if (start_x < max_x && start_y < max_y) {
         int row = (start_y << subsampling_y) / 4;
         int column = (start_x << subsampling_x) / 4;
         int mask = this.sequence_header_.use_128x128_superblock ? 31 : 15;
         int sub_block_row4x4 = row & mask;
         int sub_block_column4x4 = column & mask;
         int step_x = D.kTransformWidth4x4[txSize];
         int step_y = D.kTransformHeight4x4[txSize];
         boolean do_decode = mode == 1 || mode == 2;
         if (do_decode && !bp.is_inter) {
            if (bp.prediction_parameters.palette_mode_info.size[D.GetPlaneType(plane)] > 0) {
               this.palettePrediction(block, plane, start_x, start_y, x, y, txSize);
            } else {
               int modex = plane == 0 ? bp.y_mode : (bp.prediction_parameters.uv_mode == 13 ? 0 : bp.prediction_parameters.uv_mode);
               int tr_row4x4 = sub_block_row4x4 >> subsampling_y;
               int tr_column4x4 = (sub_block_column4x4 >> subsampling_x) + step_x + 1;
               int bl_row4x4 = (sub_block_row4x4 >> subsampling_y) + step_y + 1;
               int bl_column4x4 = sub_block_column4x4 >> subsampling_x;
               boolean has_left = x > 0 || block.leftAvail[plane];
               boolean has_top = y > 0 || block.topAvail[plane];
               this.intraPrediction(
                  block,
                  plane,
                  start_x,
                  start_y,
                  has_left,
                  has_top,
                  block.scratch_buffer.block_decoded[plane][tr_row4x4 * 34 + tr_column4x4],
                  block.scratch_buffer.block_decoded[plane][bl_row4x4 * 34 + bl_column4x4],
                  modex,
                  txSize
               );
               if (plane != 0 && bp.prediction_parameters.uv_mode == 13) {
                  this.chromaFromLumaPrediction(block, plane, start_x, start_y, txSize);
               }
            }

            if (plane == 0) {
               block.bp.prediction_parameters.max_luma_width = start_x + step_x * 4;
               block.bp.prediction_parameters.max_luma_height = start_y + step_y * 4;
               block.scratch_buffer.cfl_luma_buffer_valid = false;
            }
         }

         if (!bp.skip) {
            int sb_row_index = this.SuperBlockRowIndex(block.row4x4);
            int sb_column_index = this.SuperBlockColumnIndex(block.column4x4);
            if (mode == 1) {
               D.QueueTransformParameters tx_params = this.residual_buffer_threaded_[sb_row_index][sb_column_index].transform_parameters_;
               this.reconstructBlock(block, plane, start_x, start_y, txSize, tx_params.Front().type, tx_params.Front().non_zero_coeff_count);
               tx_params.Pop();
            } else {
               int[] tx_type = new int[]{0};
               int non_zero_coeff_count = this.readTransformCoefficients(block, plane, start_x, start_y, txSize, tx_type);
               if (non_zero_coeff_count < 0) {
                  return false;
               }

               if (mode == 2) {
                  this.reconstructBlock(block, plane, start_x, start_y, txSize, tx_type[0], non_zero_coeff_count);
               } else {
                  this.residual_buffer_threaded_[sb_row_index][sb_column_index]
                     .transform_parameters_
                     .Push(new D.TransformParameters(tx_type[0], non_zero_coeff_count));
               }
            }
         }

         if (do_decode) {
            boolean[] block_decoded = block.scratch_buffer.block_decoded[plane];
            int block_decodedPos = ((sub_block_row4x4 >> subsampling_y) + 1) * 34 + (sub_block_column4x4 >> subsampling_x) + 1;
            setBlockValuesBoolean(step_y, step_x, true, block_decoded, block_decodedPos, 34);
         }

         return true;
      } else {
         return true;
      }
   }

   private boolean transformTree(Block block, int startX, int startY, int planeSize, int mode) {
      Stack<D.TransformTreeNode> stack = new Stack<>();
      stack.push(new D.TransformTreeNode(startX, startY, planeSize));

      do {
         D.TransformTreeNode node = stack.pop();
         int row = node.y / 4;
         int column = node.x / 4;
         if (row < this.frame_header_.rows4x4 && column < this.frame_header_.columns4x4) {
            int inter_tx_size = this.inter_transform_sizes_[row][column];
            int width = D.kTransformWidth[node.tx_size];
            int height = D.kTransformHeight[node.tx_size];
            if (width <= D.kTransformWidth[inter_tx_size] && height <= D.kTransformHeight[inter_tx_size]) {
               if (!this.transformBlock(block, 0, node.x, node.y, node.tx_size, 0, 0, mode)) {
                  LogWriter.writeLog("Transform Tree : error");
                  return false;
               }
            } else {
               int split_tx_size = D.kSplitTransformSize[node.tx_size];
               int half_width = width / 2;
               if (width > height) {
                  stack.push(new D.TransformTreeNode(node.x + half_width, node.y, split_tx_size));
                  stack.push(new D.TransformTreeNode(node.x, node.y, split_tx_size));
               } else {
                  int half_height = height / 2;
                  if (width < height) {
                     stack.push(new D.TransformTreeNode(node.x, node.y + half_height, split_tx_size));
                     stack.push(new D.TransformTreeNode(node.x, node.y, split_tx_size));
                  } else {
                     stack.push(new D.TransformTreeNode(node.x + half_width, node.y + half_height, split_tx_size));
                     stack.push(new D.TransformTreeNode(node.x, node.y + half_height, split_tx_size));
                     stack.push(new D.TransformTreeNode(node.x + half_width, node.y, split_tx_size));
                     stack.push(new D.TransformTreeNode(node.x, node.y, split_tx_size));
                  }
               }
            }
         }
      } while (!stack.isEmpty());

      return true;
   }

   private void reconstructBlock(Block block, int plane, int startX, int startY, int txSize, int txType, int nonZeroCoeffCount) {
      if (nonZeroCoeffCount != 0) {
         Reconstruct.reconsruct(
            txType,
            txSize,
            this.frame_header_.segmentation.lossless[block.bp.prediction_parameters.segment_id],
            block.residual,
            block.residualPos,
            startX,
            startY,
            this.buffer_[plane],
            nonZeroCoeffCount
         );
         if (this.split_parse_and_decode_) {
            block.residualPos = block.residualPos + D.kTransformWidth[txSize] * D.kTransformHeight[txSize] * this.residual_size_;
         }
      }
   }

   private boolean residual(Block block, int mode) {
      int widthChunks = Math.max(1, block.width >> 6);
      int heightChunks = Math.max(1, block.height >> 6);
      int sizeChunk4X4 = widthChunks <= 1 && heightChunks <= 1 ? block.size : 18;
      D.BlockParams bp = block.bp;

      for (int chunkY = 0; chunkY < heightChunks; chunkY++) {
         for (int chunkX = 0; chunkX < widthChunks; chunkX++) {
            int num_planes = block.HasChroma() ? this.PlaneCount() : 1;
            int plane = 0;

            do {
               int subsampling_x = this.subsampling_x_[plane];
               int subsampling_y = this.subsampling_y_[plane];
               int tx_size = plane == 0 ? this.inter_transform_sizes_[block.row4x4][block.column4x4] : bp.uv_transform_size;
               int plane_size = D.kPlaneResidualSize[sizeChunk4X4][subsampling_x][subsampling_y];
               if (bp.is_inter && !this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id] && plane == 0) {
                  int row_chunk4x4 = block.row4x4 + chunkY * 16;
                  int column_chunk4x4 = block.column4x4 + chunkX * 16;
                  int base_x = (column_chunk4x4 >> subsampling_x) * 4;
                  int base_y = (row_chunk4x4 >> subsampling_y) * 4;
                  if (!this.transformTree(block, base_x, base_y, plane_size, mode)) {
                     LogWriter.writeLog("Residual : Error");
                     return false;
                  }
               } else {
                  int base_x = (block.column4x4 >> subsampling_x) * 4;
                  int base_y = (block.row4x4 >> subsampling_y) * 4;
                  int step_x = D.kTransformWidth4x4[tx_size];
                  int step_y = D.kTransformHeight4x4[tx_size];
                  int num4x4_wide = D.kNum4x4BlocksWide[plane_size];
                  int num4x4_high = D.kNum4x4BlocksHigh[plane_size];

                  for (int y = 0; y < num4x4_high; y += step_y) {
                     for (int x = 0; x < num4x4_wide; x += step_x) {
                        if (!this.transformBlock(
                           block, plane, base_x, base_y, tx_size, x + (chunkX * 16 >> subsampling_x), y + (chunkY * 16 >> subsampling_y), mode
                        )) {
                           LogWriter.writeLog("Residual : Error2");
                           return false;
                        }
                     }
                  }
               }
            } while (++plane < num_planes);
         }
      }

      return true;
   }

   private boolean isMvValid(Block block, boolean is_compound) {
      return true;
   }

   private boolean assignInterMv(Block block, boolean is_compound) {
      int[] min = new int[2];
      int[] max = new int[2];
      getClampParameters(block, min, max);
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = bp.prediction_parameters;
      bp.mv.mv64 = 0L;
      if (is_compound) {
         for (int i = 0; i < 2; i++) {
            int mode = getSinglePredictionMode(i, bp.y_mode);
            D.MotionVector predicted_mv;
            if (mode == 16) {
               predicted_mv = prediction_parameters.global_mv[i];
            } else {
               int ref_mv_index = mode != 14 && (mode != 17 || prediction_parameters.ref_mv_count > 1) ? prediction_parameters.ref_mv_index : 0;
               predicted_mv = prediction_parameters.reference_mv(ref_mv_index, i);
               if (ref_mv_index < prediction_parameters.ref_mv_count) {
                  predicted_mv.mv[0] = D.Clip3(predicted_mv.mv[0], min[0], max[0]);
                  predicted_mv.mv[1] = D.Clip3(predicted_mv.mv[1], min[1], max[1]);
               }
            }

            if (mode == 17) {
               this.ReadMotionVector(block, i);
               bp.mv.mv[i].mv[0] = bp.mv.mv[i].mv[0] + predicted_mv.mv[0];
               bp.mv.mv[i].mv[1] = bp.mv.mv[i].mv[1] + predicted_mv.mv[1];
            } else {
               bp.mv.mv[i] = predicted_mv;
            }
         }
      } else {
         int modex = getSinglePredictionMode(0, bp.y_mode);
         D.MotionVector predicted_mvx;
         if (modex == 16) {
            predicted_mvx = prediction_parameters.global_mv[0];
         } else {
            int ref_mv_index = modex != 14 && (modex != 17 || prediction_parameters.ref_mv_count > 1) ? prediction_parameters.ref_mv_index : 0;
            predicted_mvx = prediction_parameters.reference_mv(ref_mv_index);
            if (ref_mv_index < prediction_parameters.ref_mv_count) {
               predicted_mvx.mv[0] = D.Clip3(predicted_mvx.mv[0], min[0], max[0]);
               predicted_mvx.mv[1] = D.Clip3(predicted_mvx.mv[1], min[1], max[1]);
            }
         }

         if (modex == 17) {
            this.ReadMotionVector(block, 0);
            bp.mv.mv[0].mv[0] = bp.mv.mv[0].mv[0] + predicted_mvx.mv[0];
            bp.mv.mv[0].mv[1] = bp.mv.mv[0].mv[1] + predicted_mvx.mv[1];
         } else {
            bp.mv.mv[0] = predicted_mvx;
         }
      }

      return this.isMvValid(block, is_compound);
   }

   private boolean assignIntraMv(Block block) {
      int[] min = new int[2];
      int[] max = new int[2];
      getClampParameters(block, min, max);
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = bp.prediction_parameters;
      D.MotionVector ref_mv_0 = prediction_parameters.reference_mv(0);
      bp.mv.mv64 = 0L;
      this.ReadMotionVector(block, 0);
      if (ref_mv_0.mv32 == 0) {
         D.MotionVector ref_mv_1 = prediction_parameters.reference_mv(1);
         if (ref_mv_1.mv32 == 0) {
            int super_block_size4x4 = D.kNum4x4BlocksHigh[this.SuperBlockSize()];
            if (block.row4x4 - super_block_size4x4 < this.row4x4_start_) {
               bp.mv.mv[0].mv[1] = bp.mv.mv[0].mv[1] - super_block_size4x4 * 32;
               bp.mv.mv[0].mv[1] = bp.mv.mv[0].mv[1] - 2048;
            } else {
               bp.mv.mv[0].mv[0] = bp.mv.mv[0].mv[0] - super_block_size4x4 * 32;
            }
         } else {
            bp.mv.mv[0].mv[0] = bp.mv.mv[0].mv[0] + D.Clip3(ref_mv_1.mv[0], min[0], max[0]);
            bp.mv.mv[0].mv[1] = bp.mv.mv[0].mv[1] + D.Clip3(ref_mv_1.mv[1], min[0], max[0]);
         }
      } else {
         bp.mv.mv[0].mv[0] = bp.mv.mv[0].mv[0] + D.Clip3(ref_mv_0.mv[0], min[0], max[0]);
         bp.mv.mv[0].mv[1] = bp.mv.mv[0].mv[1] + D.Clip3(ref_mv_0.mv[1], min[1], max[1]);
      }

      return this.isMvValid(block, false);
   }

   private void resetEntropyContext(Block block) {
      int num_planes = block.HasChroma() ? this.PlaneCount() : 1;
      int plane = 0;

      do {
         int subsampling_x = this.subsampling_x_[plane];
         int start_x = block.column4x4 >> subsampling_x;
         int end_x = Math.min(block.column4x4 + block.width4x4 >> subsampling_x, this.frame_header_.columns4x4);
         Mem.set(this.coefficient_levels_[1][plane], start_x, (byte)0, end_x - start_x);
         Mem.set(this.dc_categories_[1][plane], start_x, (byte)0, end_x - start_x);
         int subsampling_y = this.subsampling_y_[plane];
         int start_y = block.row4x4 >> subsampling_y;
         int end_y = Math.min(block.row4x4 + block.height4x4 >> subsampling_y, this.frame_header_.rows4x4);
         Mem.set(this.coefficient_levels_[0][plane], start_y, (byte)0, end_y - start_y);
         Mem.set(this.dc_categories_[0][plane], start_y, (byte)0, end_y - start_y);
      } while (++plane < num_planes);
   }

   private boolean computePrediction(Block block) {
      D.BlockParams bp = block.bp;
      if (!bp.is_inter) {
         return true;
      } else {
         int mask = (1 << 4 + (this.sequence_header_.use_128x128_superblock ? 1 : 0)) - 1;
         int sub_block_row4x4 = block.row4x4 & mask;
         int sub_block_column4x4 = block.column4x4 & mask;
         int plane_count = block.HasChroma() ? this.PlaneCount() : 1;
         boolean[] is_local_valid = new boolean[]{false};
         D.GlobalMotion local_warp_params = new D.GlobalMotion();
         int plane = 0;

         label85:
         while (true) {
            int subsampling_x = this.subsampling_x_[plane];
            int subsampling_y = this.subsampling_y_[plane];
            int plane_size = block.residual_size[plane];
            int block_width4x4 = D.kNum4x4BlocksWide[plane_size];
            int block_height4x4 = D.kNum4x4BlocksHigh[plane_size];
            int block_width = block_width4x4 * 4;
            int block_height = block_height4x4 * 4;
            int base_x = (block.column4x4 >> subsampling_x) * 4;
            int base_y = (block.row4x4 >> subsampling_y) * 4;
            if (bp.reference_frame[1] == 0) {
               int tr_row4x4 = sub_block_row4x4 >> subsampling_y;
               int tr_column4x4 = (sub_block_column4x4 >> subsampling_x) + block_width4x4 + 1;
               int bl_row4x4 = (sub_block_row4x4 >> subsampling_y) + block_height4x4;
               int bl_column4x4 = (sub_block_column4x4 >> subsampling_x) + 1;
               int tx_size = D.k4x4SizeToTransformSize[D.k4x4WidthLog2[plane_size]][D.k4x4HeightLog2[plane_size]];
               boolean has_left = block.leftAvail[plane];
               boolean has_top = block.topAvail[plane];
               this.intraPrediction(
                  block,
                  plane,
                  base_x,
                  base_y,
                  has_left,
                  has_top,
                  block.scratch_buffer.block_decoded[plane][tr_row4x4 * 34 + tr_column4x4],
                  block.scratch_buffer.block_decoded[plane][bl_row4x4 * 34 + bl_column4x4],
                  D.kInterIntraToIntraMode[block.bp.prediction_parameters.inter_intra_mode],
                  tx_size
               );
            }

            int candidate_row = block.row4x4;
            int candidate_column = block.column4x4;
            boolean some_use_intra = bp.reference_frame[0] == 0;
            if (!some_use_intra && plane != 0) {
               candidate_row = candidate_row >> subsampling_y << subsampling_y;
               candidate_column = candidate_column >> subsampling_x << subsampling_x;
               if (candidate_row != block.row4x4) {
                  D.BlockParams bp_top = this.block_parameters_holder_.Find(candidate_row, block.column4x4);
                  some_use_intra = bp_top.reference_frame[0] == 0;
                  if (!some_use_intra && candidate_column != block.column4x4) {
                     D.BlockParams bp_top_left = this.block_parameters_holder_.Find(candidate_row, candidate_column);
                     some_use_intra = bp_top_left.reference_frame[0] == 0;
                  }
               }

               if (!some_use_intra && candidate_column != block.column4x4) {
                  D.BlockParams bp_left = this.block_parameters_holder_.Find(block.row4x4, candidate_column);
                  some_use_intra = bp_left.reference_frame[0] == 0;
               }
            }

            int prediction_width;
            int prediction_height;
            if (some_use_intra) {
               candidate_row = block.row4x4;
               candidate_column = block.column4x4;
               prediction_width = block_width;
               prediction_height = block_height;
            } else {
               prediction_width = block.width >> subsampling_x;
               prediction_height = block.height >> subsampling_y;
            }

            int r = 0;
            int y = 0;

            label83:
            while (true) {
               int c = 0;
               int x = 0;

               while (
                  this.interPrediction(
                     block,
                     plane,
                     base_x + x,
                     base_y + y,
                     prediction_width,
                     prediction_height,
                     candidate_row + r,
                     candidate_column + c,
                     is_local_valid,
                     local_warp_params
                  )
               ) {
                  c++;
                  x += prediction_width;
                  if (x >= block_width) {
                     r++;
                     y += prediction_height;
                     if (y < block_height) {
                        continue label83;
                     }

                     if (++plane >= plane_count) {
                        return true;
                     }
                     continue label85;
                  }
               }

               return false;
            }
         }
      }
   }

   private boolean interPrediction(
      Block block,
      int plane,
      int x,
      int y,
      int prediction_width,
      int prediction_height,
      int candidate_row,
      int candidate_column,
      boolean[] isLocalValid,
      D.GlobalMotion local_warp_params
   ) {
      LogWriter.writeLog("Tile : Inter prediction is not implemented");
      int bitdepth = this.sequence_header_.color_config.bitdepth;
      D.BlockParams bp = block.bp;
      D.BlockParams bp_reference = this.block_parameters_holder_.Find(candidate_row, candidate_column);
      boolean is_compound = bp_reference.reference_frame[1] > 0;
      boolean is_inter_intra = bp.reference_frame[1] == 0;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      int destPos = getStartPoint(this.buffer_, plane, x, y, bitdepth);
      int[] dest = this.buffer_[plane].data_;
      int dest_stride = this.buffer_[plane].columns();

      for (int index = 0; index < 1 + (is_compound ? 1 : 0); index++) {
         int reference_type = bp_reference.reference_frame[index];
         D.GlobalMotion global_motion_params = this.frame_header_.global_motion[reference_type];
         D.GlobalMotion warp_params = this.getWarpParams(
            block, plane, prediction_width, prediction_height, prediction_parameters, reference_type, isLocalValid, global_motion_params, local_warp_params
         );
         if (warp_params == null) {
            int reference_index = prediction_parameters.use_intra_block_copy ? -1 : this.frame_header_.reference_frame_index[reference_type - 1];
            if (!this.blockInterPrediction(
               block,
               plane,
               reference_index,
               bp_reference.mv.mv[index],
               x,
               y,
               prediction_width,
               prediction_height,
               candidate_row,
               candidate_column,
               block.scratch_buffer.prediction_buffer[index],
               0,
               is_compound,
               is_inter_intra,
               dest,
               destPos,
               dest_stride
            )) {
               return false;
            }
         }
      }

      int subsampling_x = this.subsampling_x_[plane];
      int subsampling_y = this.subsampling_y_[plane];
      int prediction_mask_stride = 0;
      int[] prediction_mask = null;
      if (prediction_parameters.compound_prediction_type != 0
         && prediction_parameters.compound_prediction_type != 3
         && prediction_parameters.compound_prediction_type == 1) {
         prediction_mask = block.scratch_buffer.weight_mask;
         prediction_mask_stride = block.width;
      }

      return true;
   }

   private void compoundInterPrediction(
      Block block,
      int[] predictionMask,
      int predictionMaskStride,
      int predictionWidth,
      int predictionHeight,
      int subsamplingX,
      int subsamplingY,
      int candidateRow,
      int candidateColumn,
      int[] dest,
      int destPos,
      int destStride
   ) {
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      int[][] prediction = new int[][]{block.scratch_buffer.compound_prediction_buffer_8bpp[0], block.scratch_buffer.compound_prediction_buffer_8bpp[1]};
      LogWriter.writeLog("compound inter prediction not supported yet");
   }

   private void scaleMotionVector(D.MotionVector mv, int plane, int referenceFrameIndex, int x, int y, int[] startX, int[] startY, int[] stepX, int[] stepY) {
      int reference_upscaled_width = referenceFrameIndex == -1
         ? this.frame_header_.upscaled_width
         : this.reference_frames_[referenceFrameIndex].upscaled_width();
      int reference_height = referenceFrameIndex == -1 ? this.frame_header_.height : this.reference_frames_[referenceFrameIndex].frame_height();
      boolean isScaledX = reference_upscaled_width != this.frame_header_.width;
      boolean isScaledY = reference_height != this.frame_header_.height;
      int half_sample = 8;
      int orig_x = (x << 4) + (2 * mv.mv[1] >> this.subsampling_x_[plane]);
      int orig_y = (y << 4) + (2 * mv.mv[0] >> this.subsampling_y_[plane]);
      int rounding_offset = 32;
      if (isScaledX) {
         int scale_x = ((reference_upscaled_width << 14) + this.frame_header_.width / 2) / this.frame_header_.width;
         stepX[0] = D.RightShiftWithRoundingSigned(scale_x, 4);
         orig_x += half_sample;
         long base_x = orig_x * 1L * scale_x - (half_sample << 14);
         startX[0] = D.RightShiftWithRoundingSigned(base_x, 8) + rounding_offset;
      } else {
         stepX[0] = 1024;
         startX[0] = leftShift(orig_x, 6) + rounding_offset;
      }

      if (isScaledY) {
         int scale_y = ((reference_height << 14) + this.frame_header_.height / 2) / this.frame_header_.height;
         stepY[0] = D.RightShiftWithRoundingSigned(scale_y, 4);
         orig_y += half_sample;
         long base_y = orig_y * 1L * scale_y - (half_sample << 14);
         startY[0] = D.RightShiftWithRoundingSigned(base_y, 8) + rounding_offset;
      } else {
         stepY[0] = 1024;
         startY[0] = leftShift(orig_y, 6) + rounding_offset;
      }
   }

   private static boolean getRefBlockPos(
      int referenceFrameIndex,
      boolean isScaled,
      int width,
      int height,
      int refStartX,
      int refLastX,
      int refStartY,
      int refLastY,
      int startX,
      int startY,
      int stepX,
      int stepY,
      int leftBorder,
      int rightBorder,
      int topBorder,
      int bottomBorder,
      int[] refBlockStartX,
      int[] refBlockStartY,
      int[] refBlockEndX,
      int[] refBlockEndY
   ) {
      refBlockStartX[0] = getPixelPositionFromHighScale(startX, 0, 0);
      refBlockStartY[0] = getPixelPositionFromHighScale(startY, 0, 0);
      if (referenceFrameIndex == -1) {
         return false;
      } else {
         refBlockStartX[0] -= 3;
         refBlockStartY[0] -= 3;
         refBlockEndX[0] = getPixelPositionFromHighScale(startX, stepX, width - 1) + 8;
         refBlockEndY[0] = getPixelPositionFromHighScale(startY, stepY, height - 1) + 4;
         if (isScaled) {
            int block_height = ((height - 1) * stepY + 1024 - 1 >> 10) + 8;
            refBlockEndX[0] += 7;
            refBlockEndY[0] = refBlockStartY[0] + block_height - 1;
         }

         return refBlockStartX[0] < refStartX - leftBorder
            || refBlockEndX[0] > refLastX + rightBorder
            || refBlockStartY[0] < refStartY - topBorder
            || refBlockEndY[0] > refLastY + bottomBorder;
      }
   }

   boolean blockInterPrediction(
      Block block,
      int plane,
      int referenceFrameIndex,
      D.MotionVector mv,
      int x,
      int y,
      int width,
      int height,
      int candidate_row,
      int candidate_column,
      int[] prediction,
      int predictPos,
      boolean is_compound,
      boolean is_inter_intra,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      LogWriter.writeLog("block inter prediction not supported");
      D.BlockParams bp = this.block_parameters_holder_.Find(candidate_row, candidate_column);
      int[] start_x = new int[]{0};
      int[] start_y = new int[]{0};
      int[] step_x = new int[]{0};
      int[] step_y = new int[]{0};
      this.scaleMotionVector(mv, plane, referenceFrameIndex, x, y, start_x, start_y, step_x, step_y);
      int horizontal_filter_index = bp.interpolation_filter[1];
      int vertical_filter_index = bp.interpolation_filter[0];
      int subsampling_x = this.subsampling_x_[plane];
      int subsampling_y = this.subsampling_y_[plane];
      Yuv reference_buffer = referenceFrameIndex == -1 ? this.current_frame_.buffer() : this.reference_frames_[referenceFrameIndex].buffer();
      int reference_upscaled_width = referenceFrameIndex == -1
         ? this.frame_header_.columns4x4 * 4
         : this.reference_frames_[referenceFrameIndex].upscaled_width();
      int reference_height = referenceFrameIndex == -1 ? this.frame_header_.rows4x4 * 4 : this.reference_frames_[referenceFrameIndex].frame_height();
      int ref_start_x = 0;
      int ref_last_x = D.SubsampledValue(reference_upscaled_width, subsampling_x) - 1;
      int ref_start_y = 0;
      int ref_last_y = D.SubsampledValue(reference_height, subsampling_y) - 1;
      boolean is_scaled = referenceFrameIndex != -1 && (this.frame_header_.width != reference_upscaled_width || this.frame_header_.height != reference_height);
      int bitdepth = this.sequence_header_.color_config.bitdepth;
      int pixel_size = 1;
      int[] ref_block_start_x = new int[]{0};
      int[] ref_block_start_y = new int[]{0};
      int[] ref_block_end_x = new int[]{0};
      int[] ref_block_end_y = new int[]{0};
      boolean extend_block = getRefBlockPos(
         referenceFrameIndex,
         is_scaled,
         width,
         height,
         ref_start_x,
         ref_last_x,
         ref_start_y,
         ref_last_y,
         start_x[0],
         start_y[0],
         step_x[0],
         step_y[0],
         reference_buffer.left_border_[plane],
         reference_buffer.right_border_[plane],
         reference_buffer.top_border_[plane],
         reference_buffer.bottom_border_[plane],
         ref_block_start_x,
         ref_block_start_y,
         ref_block_end_x,
         ref_block_end_y
      );
      return true;
   }

   D.GlobalMotion getWarpParams(
      Block block,
      int plane,
      int predictionWidth,
      int predictionHeight,
      D.PredictionParams predictionParams,
      int referenceType,
      boolean[] isLocalValid,
      D.GlobalMotion globalMotionParams,
      D.GlobalMotion localWarpParams
   ) {
      if (predictionWidth >= 8 && predictionHeight >= 8 && this.frame_header_.force_integer_mv != 1) {
         if (plane == 0) {
            isLocalValid[0] = predictionParams.motion_mode == 2
               && warpEstimation(
                  predictionParams.num_warp_samples,
                  predictionWidth * 4,
                  predictionHeight * 4,
                  block.row4x4,
                  block.column4x4,
                  block.bp.mv.mv[0],
                  predictionParams.warp_estimate_candidates,
                  localWarpParams
               )
               && setupShear(localWarpParams);
         }

         if (predictionParams.motion_mode == 2 && isLocalValid[0]) {
            return localWarpParams;
         } else {
            if (!this.IsScaled(referenceType)) {
               int global_motion_type = referenceType != 0 ? globalMotionParams.type : 4;
               boolean is_global_valid = isGlobalMvBlock(block.bp, global_motion_type) && setupShear(globalMotionParams);
               if (is_global_valid) {
                  return globalMotionParams;
               }
            }

            return null;
         }
      } else {
         return null;
      }
   }

   static int leastSquareProduct(int a, int b) {
      return (a * b >> 2) + a + b;
   }

   static int diagonalClamp(int value) {
      return D.Clip3(value, 57345, 73727);
   }

   static int nonDiagonalClamp(int value) {
      return D.Clip3(value, -8191, 8191);
   }

   static int getShearParameter(int value) {
      return leftShift(D.RightShiftWithRoundingSigned(D.Clip3(value, -32768, 32767), 6), 6);
   }

   static int leftShift(int value, int bits) {
      return value << bits;
   }

   static void generateApproximateDivisor(int value, int[] division_factor, int[] divisionShift) {
      int n = D.FloorLog2(Math.abs(value));
      int e = Math.abs(value) - (1 << n);
      int entry = n > 8 ? D.RightShiftWithRounding(e, n - 8) : e << 8 - n;
      divisionShift[0] = n + 14;
      division_factor[0] = value < 0 ? -D.kDivisorLookup[entry] : D.kDivisorLookup[entry];
   }

   static boolean warpEstimation(
      int numSamples, int blockWidth4X4, int blockHeight4X4, int row4x4, int column4x4, D.MotionVector mv, int[][] candidates, D.GlobalMotion warp_params
   ) {
      int[][] a = new int[2][2];
      int[] bx = new int[2];
      int[] by = new int[2];
      int mid_y = row4x4 * 4 + blockHeight4X4 * 2 - 1;
      int mid_x = column4x4 * 4 + blockWidth4X4 * 2 - 1;
      int subpixel_mid_y = mid_y * 8;
      int subpixel_mid_x = mid_x * 8;
      int reference_subpixel_mid_y = subpixel_mid_y + mv.mv[0];
      int reference_subpixel_mid_x = subpixel_mid_x + mv.mv[1];

      for (int i = 0; i < numSamples; i++) {
         int sy = candidates[i][0] - subpixel_mid_y;
         int sx = candidates[i][1] - subpixel_mid_x;
         int dy = candidates[i][2] - reference_subpixel_mid_y;
         int dx = candidates[i][3] - reference_subpixel_mid_x;
         if (Math.abs(sx - dx) < 256 && Math.abs(sy - dy) < 256) {
            a[0][0] = a[0][0] + leastSquareProduct(sx, sx) + 8;
            a[0][1] = a[0][1] + leastSquareProduct(sx, sy) + 4;
            a[1][1] = a[1][1] + leastSquareProduct(sy, sy) + 8;
            bx[0] += leastSquareProduct(sx, dx) + 8;
            bx[1] += leastSquareProduct(sy, dx) + 4;
            by[0] += leastSquareProduct(sx, dy) + 4;
            by[1] += leastSquareProduct(sy, dy) + 8;
         }
      }

      int determinant = a[0][0] * a[1][1] - a[0][1] * a[0][1];
      if (determinant == 0) {
         return false;
      } else {
         int[] division_shift = new int[]{0};
         int[] division_factor = new int[]{0};
         generateApproximateDivisor(determinant, division_factor, division_shift);
         division_shift[0] -= 16;
         int params_2 = a[1][1] * bx[0] - a[0][1] * bx[1];
         int params_3 = -a[0][1] * bx[0] + a[0][0] * bx[1];
         int params_4 = a[1][1] * by[0] - a[0][1] * by[1];
         int params_5 = -a[0][1] * by[0] + a[0][0] * by[1];
         int[] params = warp_params.params;
         if (division_shift[0] <= 0) {
            division_factor[0] <<= -division_shift[0];
            params[2] = params_2 * division_factor[0];
            params[3] = params_3 * division_factor[0];
            params[4] = params_4 * division_factor[0];
            params[5] = params_5 * division_factor[0];
         } else {
            params[2] = D.RightShiftWithRoundingSigned(params_2 * division_factor[0], division_shift[0]);
            params[3] = D.RightShiftWithRoundingSigned(params_3 * division_factor[0], division_shift[0]);
            params[4] = D.RightShiftWithRoundingSigned(params_4 * division_factor[0], division_shift[0]);
            params[5] = D.RightShiftWithRoundingSigned(params_5 * division_factor[0], division_shift[0]);
         }

         params[2] = diagonalClamp(params[2]);
         params[3] = nonDiagonalClamp(params[3]);
         params[4] = nonDiagonalClamp(params[4]);
         params[5] = diagonalClamp(params[5]);
         int vx = mv.mv[1] * 8192 - (mid_x * (params[2] - 65536) + mid_y * params[3]);
         int vy = mv.mv[0] * 8192 - (mid_x * params[4] + mid_y * (params[5] - 65536));
         params[0] = D.Clip3(vx, -8388608, 8388607);
         params[1] = D.Clip3(vy, -8388608, 8388607);
         return true;
      }
   }

   static boolean setupShear(D.GlobalMotion warp_params) {
      int[] division_shift = new int[]{0};
      int[] division_factor = new int[]{0};
      int[] params = warp_params.params;
      generateApproximateDivisor(params[2], division_factor, division_shift);
      int alpha = params[2] - 65536;
      int beta = params[3];
      int v = leftShift(params[4], 16);
      int gamma = D.RightShiftWithRoundingSigned(v * division_factor[0], division_shift[0]);
      long w = params[3] * 1L * params[4];
      int delta = params[5] - D.RightShiftWithRoundingSigned(w * division_factor[0], division_shift[0]) - 65536;
      warp_params.alpha = getShearParameter(alpha);
      warp_params.beta = getShearParameter(beta);
      warp_params.gamma = getShearParameter(gamma);
      warp_params.delta = getShearParameter(delta);
      return 4 * Math.abs(warp_params.alpha) + 7 * Math.abs(warp_params.beta) < 65536
         && 4 * Math.abs(warp_params.gamma) + 4 * Math.abs(warp_params.delta) < 65536;
   }

   private void populateDeblockFilterLevel(Block block) {
      if (this.post_filter_.DoDeblock()) {
         D.BlockParams bp = block.bp;
         int mode_id = D.kPredictionModeDeltasMask.Contains(bp.y_mode) ? 1 : 0;

         for (int i = 0; i < 4; i++) {
            if (this.delta_lf_all_zero_) {
               bp.deblock_filter_level[i] = this.post_filter_
                  .GetZeroDeltaDeblockFilterLevel(bp.prediction_parameters.segment_id, i, bp.reference_frame[0], mode_id);
            } else {
               bp.deblock_filter_level[i] = this.deblock_filter_levels_[bp.prediction_parameters.segment_id][i][bp.reference_frame[0]][mode_id];
            }
         }
      }
   }

   private void populateCdefSkip(Block block) {
      if (this.post_filter_.DoCdef()
         && !block.bp.skip
         && (this.frame_header_.cdef.bits <= 0 || this.cdef_index_.get(block.row4x4 / 16, block.column4x4 / 16) != -1)) {
         int bw4 = Math.max(block.width4x4 / 2 + (block.column4x4 & 1), 1);
         int mask = block.width4x4 == 32 ? 255 : 255 >> 8 - bw4 << (block.column4x4 / 2 & 7);
         int hPos = block.row4x4 >> 1;
         int wPos = block.column4x4 >> 4;
         int stride = this.cdef_skip_.columns();
         int[] cdef_skip = this.cdef_skip_.data_;
         int cdef_skipPos = hPos * stride + wPos;
         int row = 0;

         do {
            cdef_skip[cdef_skipPos] |= mask;
            if (block.width4x4 == 32) {
               cdef_skip[cdef_skipPos + 1] = 255;
            }

            cdef_skipPos += stride;
            row += 2;
         } while (row < block.height4x4);
      }
   }

   private boolean processBlock(int row4x4, int column4x4, int block_size, D.TileScratchBuffer scratch_buffer, int[] residual, int residualPos) {
      if (row4x4 < this.frame_header_.rows4x4 && column4x4 < this.frame_header_.columns4x4) {
         if (this.split_parse_and_decode_) {
            int sb_row_index = this.SuperBlockRowIndex(row4x4);
            int sb_column_index = this.SuperBlockColumnIndex(column4x4);
            this.residual_buffer_threaded_[sb_row_index][sb_column_index].partition_tree_order().Push(new D.PartitionTreeNode(row4x4, column4x4, block_size));
         }

         D.BlockParams bp_ptr = this.block_parameters_holder_.Get(row4x4, column4x4, block_size);
         if (bp_ptr == null) {
            return false;
         } else {
            Block block = new Block(this, block_size, row4x4, column4x4, scratch_buffer, residual, residualPos);
            bp_ptr.size = block_size;
            if (this.split_parse_and_decode_) {
               bp_ptr.prediction_parameters = new D.PredictionParams();
            } else {
               bp_ptr.prediction_parameters = this.prediction_parameters_;
               this.prediction_parameters_ = null;
            }

            if (bp_ptr.prediction_parameters == null) {
               LogWriter.writeLog("Process Block Error");
               return false;
            } else if (!this.decodeModeInfo(block)) {
               LogWriter.writeLog("Process Block Error");
               return false;
            } else {
               this.populateDeblockFilterLevel(block);
               if (!this.readPaletteTokens(block)) {
                  LogWriter.writeLog("Process Block Error2");
                  return false;
               } else {
                  this.decodeTransformSize(block);
                  bp_ptr.uv_transform_size = this.frame_header_.segmentation.lossless[bp_ptr.prediction_parameters.segment_id]
                     ? 0
                     : D.kUVTransformSize[block.residual_size[1]];
                  if (bp_ptr.skip) {
                     this.resetEntropyContext(block);
                  }

                  this.populateCdefSkip(block);
                  if (this.split_parse_and_decode_) {
                     if (!this.residual(block, 0)) {
                        LogWriter.writeLog("Process Block Error3");
                        return false;
                     }
                  } else if (!this.computePrediction(block) || !this.residual(block, 2)) {
                     LogWriter.writeLog("Process Block Error4");
                     return false;
                  }

                  if (this.frame_header_.segmentation.enabled && this.frame_header_.segmentation.update_map) {
                     int x_limit = Math.min(this.frame_header_.columns4x4 - column4x4, block.width4x4);
                     int y_limit = Math.min(this.frame_header_.rows4x4 - row4x4, block.height4x4);
                     this.current_frame_.segmentation_map().FillBlock(row4x4, column4x4, x_limit, y_limit, bp_ptr.prediction_parameters.segment_id);
                  }

                  this.StoreMotionFieldMvsIntoCurrentFrame(block);
                  if (!this.split_parse_and_decode_) {
                     this.prediction_parameters_ = bp_ptr.prediction_parameters;
                     bp_ptr.prediction_parameters = null;
                  }

                  return true;
               }
            }
         }
      } else {
         return true;
      }
   }

   private boolean decodeBlock(int row4x4, int column4x4, int block_size, D.TileScratchBuffer scratchBuffer, int[] residual, int residualPos) {
      if (row4x4 < this.frame_header_.rows4x4 && column4x4 < this.frame_header_.columns4x4) {
         Block block = new Block(this, block_size, row4x4, column4x4, scratchBuffer, residual, residualPos);
         if (this.computePrediction(block) && this.residual(block, 1)) {
            block.bp.prediction_parameters = null;
            return true;
         } else {
            LogWriter.writeLog("Tile error: Decode Block");
            return false;
         }
      } else {
         return true;
      }
   }

   private boolean processPartition(int row4X4Start, int column4X4Start, D.TileScratchBuffer scratchBuffer, int[] residual, int residualPos) {
      Stack<D.PartitionTreeNode> stack = new Stack<>();
      stack.push(new D.PartitionTreeNode(row4X4Start, column4X4Start, this.SuperBlockSize()));

      do {
         D.PartitionTreeNode node = stack.pop();
         int row4x4 = node.row4x4;
         int column4x4 = node.column4x4;
         int block_size = node.block_size;
         if (row4x4 < this.frame_header_.rows4x4 && column4x4 < this.frame_header_.columns4x4) {
            int block_width4x4 = D.kNum4x4BlocksWide[block_size];
            int half_block4x4 = block_width4x4 >> 1;
            boolean has_rows = row4x4 + half_block4x4 < this.frame_header_.rows4x4;
            boolean has_columns = column4x4 + half_block4x4 < this.frame_header_.columns4x4;
            int[] partition = new int[1];
            if (!this.readPartition(row4x4, column4x4, block_size, has_rows, has_columns, partition)) {
               LogWriter.writeLog("partition error 1");
               return false;
            }

            int sub_size = D.kSubSize[partition[0]][block_size];
            if (sub_size == 23
               || D.kPlaneResidualSize[sub_size][this.sequence_header_.color_config.subsampling_x][this.sequence_header_.color_config.subsampling_y] == 23) {
               LogWriter.writeLog("partition error 2");
               return false;
            }

            int quarter_block4x4 = half_block4x4 >> 1;
            int split_size = D.kSubSize[3][block_size];
            switch (partition[0]) {
               case 0:
                  if (!this.processBlock(row4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 3");
                     return false;
                  }
                  break;
               case 1:
                  if (!this.processBlock(row4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 4");
                     return false;
                  }
                  break;
               case 2:
                  if (!this.processBlock(row4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4, column4x4 + half_block4x4, sub_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 5");
                     return false;
                  }
                  break;
               case 3:
                  stack.push(new D.PartitionTreeNode(row4x4 + half_block4x4, column4x4 + half_block4x4, sub_size));
                  stack.push(new D.PartitionTreeNode(row4x4 + half_block4x4, column4x4, sub_size));
                  stack.push(new D.PartitionTreeNode(row4x4, column4x4 + half_block4x4, sub_size));
                  stack.push(new D.PartitionTreeNode(row4x4, column4x4, sub_size));
                  break;
               case 4:
                  if (!this.processBlock(row4x4, column4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4, column4x4 + half_block4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 6");
                     return false;
                  }
                  break;
               case 5:
                  if (!this.processBlock(row4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4 + half_block4x4, split_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 7");
                     return false;
                  }
                  break;
               case 6:
                  if (!this.processBlock(row4x4, column4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4, column4x4 + half_block4x4, sub_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 8");
                     return false;
                  }
                  break;
               case 7:
                  if (!this.processBlock(row4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4, column4x4 + half_block4x4, split_size, scratchBuffer, residual, residualPos)
                     || !this.processBlock(row4x4 + half_block4x4, column4x4 + half_block4x4, split_size, scratchBuffer, residual, residualPos)) {
                     LogWriter.writeLog("partition error 9");
                     return false;
                  }
                  break;
               case 8:
                  for (int ix = 0; ix < 4; ix++) {
                     if (!this.processBlock(row4x4 + ix * quarter_block4x4, column4x4, sub_size, scratchBuffer, residual, residualPos)) {
                        LogWriter.writeLog("partition error 10");
                        return false;
                     }
                  }
                  break;
               case 9:
                  for (int i = 0; i < 4; i++) {
                     if (!this.processBlock(row4x4, column4x4 + i * quarter_block4x4, sub_size, scratchBuffer, residual, residualPos)) {
                        LogWriter.writeLog("partition error 11");
                        return false;
                     }
                  }
            }
         }
      } while (!stack.isEmpty());

      return true;
   }

   private void resetLoopRestorationParams() {
      for (int plane = 0; plane < 3; plane++) {
         if (this.reference_unit_info_[plane] == null) {
            this.reference_unit_info_[plane] = new D.RestorationUnitInfo();
         }

         for (int i = 0; i <= 1; i++) {
            this.reference_unit_info_[plane].sgr_proj_info.multiplier[i] = D.kSgrProjDefaultMultiplier[i];

            for (int j = 0; j < 3; j++) {
               this.reference_unit_info_[plane].wiener_info.filter[i][j] = D.kWienerDefaultFilter[j];
            }
         }
      }
   }

   private void resetCdef(int row4x4, int column4x4) {
      if (this.frame_header_.cdef.bits != 0) {
         int row = row4x4 / 16;
         int column = column4x4 / 16;
         this.cdef_index_.set(row, column, -1);
         if (this.sequence_header_.use_128x128_superblock) {
            int cdef_size4x4 = D.kNum4x4BlocksWide[18];
            int border_row = (row4x4 + cdef_size4x4) / 16;
            int border_column = (column4x4 + cdef_size4x4) / 16;
            this.cdef_index_.set(row, border_column, -1);
            this.cdef_index_.set(border_row, column, -1);
            this.cdef_index_.set(border_row, border_column, -1);
         }
      }
   }

   private void clearBlockDecoded(D.TileScratchBuffer scratch_buffer, int row4x4, int column4x4) {
      for (int i = 0; i < 3; i++) {
         int len = 1156;

         for (int j = 0; j < len; j++) {
            scratch_buffer.block_decoded[i][j] = false;
         }
      }

      int sb_size4 = this.sequence_header_.use_128x128_superblock ? 32 : 16;

      for (int plane = 0; plane < this.PlaneCount(); plane++) {
         int subsampling_x = this.subsampling_x_[plane];
         int subsampling_y = this.subsampling_y_[plane];
         int sb_width4 = this.column4x4_end_ - column4x4 >> subsampling_x;
         int sb_height4 = this.row4x4_end_ - row4x4 >> subsampling_y;
         int num_elements = Math.min((sb_size4 >> this.subsampling_x_[plane]) + 1, sb_width4) + 1;
         Mem.set(scratch_buffer.block_decoded[plane], true, num_elements);

         for (int y = -1; y < Math.min(sb_size4 >> subsampling_y, sb_height4); y++) {
            scratch_buffer.block_decoded[plane][(y + 1) * 34 + 0] = true;
         }
      }
   }

   private boolean processSuperBlock(int row4x4, int column4x4, D.TileScratchBuffer scratch_buffer, int mode) {
      boolean parsing = mode == 0 || mode == 2;
      boolean decoding = mode == 1 || mode == 2;
      if (parsing) {
         this.read_deltas_ = this.frame_header_.delta_q.present;
         this.resetCdef(row4x4, column4x4);
      }

      if (decoding) {
         this.clearBlockDecoded(scratch_buffer, row4x4, column4x4);
      }

      int block_size = this.SuperBlockSize();
      if (parsing) {
         this.readLoopRestorationCoefficients(row4x4, column4x4, block_size);
      }

      if (!parsing || !decoding) {
         int sb_row_index = this.SuperBlockRowIndex(row4x4);
         int sb_column_index = this.SuperBlockColumnIndex(column4x4);
         if (parsing) {
            this.residual_buffer_threaded_[sb_row_index][sb_column_index] = this.residual_buffer_pool_.get();
            if (this.residual_buffer_threaded_[sb_row_index][sb_column_index] == null) {
               LogWriter.writeLog("process block error 2");
               return false;
            }

            int[] residual_buffer = this.residual_buffer_threaded_[sb_row_index][sb_column_index].buffer();
            int residual_bufferPos = 0;
            if (!this.processPartition(row4x4, column4x4, scratch_buffer, residual_buffer, residual_bufferPos)) {
               LogWriter.writeLog("process block error 3");
               return false;
            }
         } else {
            if (!this.decodeSuperBlock(sb_row_index, sb_column_index, scratch_buffer)) {
               LogWriter.writeLog("process block error 4");
               return false;
            }

            this.residual_buffer_threaded_[sb_row_index][sb_column_index] = null;
         }

         return true;
      } else if (!this.processPartition(row4x4, column4x4, scratch_buffer, this.residual_buffer_, this.residual_bufferPos_)) {
         LogWriter.writeLog("process block error 1");
         return false;
      } else {
         return true;
      }
   }

   private boolean decodeSuperBlock(int sb_row_index, int sbColumnIndex, D.TileScratchBuffer scratchBuffer) {
      LogWriter.writeLog("Decodesuperblock with scratch buffer is called");
      int[] residual_buffer = this.residual_buffer_threaded_[sb_row_index][sbColumnIndex].buffer();
      int residual_bufferPos = 0;
      D.QueuePartitionTreeNode partition_tree_order = this.residual_buffer_threaded_[sb_row_index][sbColumnIndex].partition_tree_order();

      while (!partition_tree_order.Empty()) {
         D.PartitionTreeNode block = partition_tree_order.Front();
         if (!this.decodeBlock(block.row4x4, block.column4x4, block.block_size, scratchBuffer, residual_buffer, residual_bufferPos)) {
            LogWriter.writeLog("Decode Superblock Error");
            return false;
         }

         partition_tree_order.Pop();
      }

      return true;
   }

   private void readLoopRestorationCoefficients(int row4x4, int column4x4, int blockSize) {
      if (!this.frame_header_.allow_intrabc) {
         D.LoopRestorationInfo restoration_info = this.post_filter_.restoration_info();
         boolean is_superres_scaled = this.frame_header_.width != this.frame_header_.upscaled_width;

         for (int plane = 0; plane < this.PlaneCount(); plane++) {
            D.LoopRestorationUnitInfo unit_info = new D.LoopRestorationUnitInfo();
            if (restoration_info.PopulateUnitInfoForSuperBlock(
               plane, blockSize, is_superres_scaled, this.frame_header_.superres_scale_denominator, row4x4, column4x4, unit_info
            )) {
               for (int unit_row = unit_info.row_start; unit_row < unit_info.row_end; unit_row++) {
                  for (int unit_column = unit_info.column_start; unit_column < unit_info.column_end; unit_column++) {
                     int unit_id = unit_row * restoration_info.num_horizontal_units(plane) + unit_column;
                     restoration_info.ReadUnitCoefficients(this.reader_, this.symbol_decoder_context_, plane, unit_id, this.reference_unit_info_);
                  }
               }
            }
         }
      }
   }

   private void StoreMotionFieldMvsIntoCurrentFrame(Block block) {
      if (this.frame_header_.refresh_frame_flags != 0 && !D.IsIntraFrame(this.frame_header_.frame_type)) {
         int row_start4x4 = block.row4x4 | 1;
         int row_limit4x4 = Math.min(block.row4x4 + block.height4x4, this.frame_header_.rows4x4);
         if (row_start4x4 < row_limit4x4) {
            int column_start4x4 = block.column4x4 | 1;
            int column_limit4x4 = Math.min(block.column4x4 + block.width4x4, this.frame_header_.columns4x4);
            if (column_start4x4 < column_limit4x4) {
               int kRefMvsLimit = 4095;
               D.BlockParams bp = block.bp;
               D.ReferenceInfo reference_info = this.current_frame_.reference_info();

               for (int i = 1; i >= 0; i--) {
                  int reference_frame_to_store = bp.reference_frame[i];
                  if (reference_frame_to_store > 0) {
                     D.MotionVector mv_to_store = bp.mv.mv[i];
                     int mv_row = Math.abs(mv_to_store.mv[0]);
                     int mv_column = Math.abs(mv_to_store.mv[1]);
                     if ((mv_row | mv_column) <= 4095 && reference_info.relative_distance_from[reference_frame_to_store] < 0) {
                        int row_start8x8 = row_start4x4 / 2;
                        int row_limit8x8 = row_limit4x4 / 2;
                        int column_start8x8 = column_start4x4 / 2;
                        int column_limit8x8 = column_limit4x4 / 2;
                        int rows = row_limit8x8 - row_start8x8;
                        int columns = column_limit8x8 - column_start8x8;
                        int stride = this.current_frame_.columns4x4() / 2;
                        int[] reference_frame_row_start = reference_info.motion_field_reference_frame[row_start8x8];
                        D.MotionVector[] mv = reference_info.motion_field_mv[row_start8x8];
                        if (columns <= 1) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, 1, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        } else if (columns == 2) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, 2, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        } else if (columns == 4) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, 4, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        } else if (columns == 8) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, 8, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        } else if (columns == 16) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, 16, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        } else if (columns < 16) {
                           storeMotionFieldMvs(
                              reference_frame_to_store, mv_to_store, stride, rows, columns, reference_frame_row_start, column_start8x8, mv, column_start8x8
                           );
                        }

                        return;
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isBackwardReference(int type) {
      return type >= 5 && type <= 7;
   }

   private static boolean isSameDirectionReferencePair(int type1, int type2) {
      return type1 >= 5 == type2 >= 5;
   }

   private static int decodeSegmentId(int diff, int reference, int max) {
      if (reference == 0) {
         return diff;
      } else if (reference >= max - 1) {
         return max - diff - 1;
      } else {
         int value = (diff & 1) != 0 ? reference + (diff + 1 >> 1) : reference - (diff >> 1);
         int reference2 = reference << 1;
         if (reference2 < max) {
            return diff <= reference2 ? value : diff;
         } else {
            return diff <= max - reference - 1 << 1 ? value : max - (diff + 1);
         }
      }
   }

   private static int getRefMvIndexContext(int nearest_mv_count, int index) {
      if (index + 1 < nearest_mv_count) {
         return 0;
      } else {
         return index + 1 == nearest_mv_count ? 1 : 2;
      }
   }

   private static boolean isBlockDimensionLessThan64(int size) {
      return size <= 14 && size != 11;
   }

   private static int getUseCompoundReferenceContext(Block block) {
      if (block.topAvail[0] && block.leftAvail[0]) {
         if (block.IsTopSingle() && block.IsLeftSingle()) {
            return (isBackwardReference(block.TopReference(0)) ? 1 : 0) ^ (isBackwardReference(block.LeftReference(0)) ? 1 : 0);
         } else if (block.IsTopSingle()) {
            return 2 + (!isBackwardReference(block.TopReference(0)) && !block.IsTopIntra() ? 0 : 1);
         } else {
            return !block.IsLeftSingle() ? 4 : 2 + (!isBackwardReference(block.LeftReference(0)) && !block.IsLeftIntra() ? 0 : 1);
         }
      } else if (block.topAvail[0]) {
         return block.IsTopSingle() ? (isBackwardReference(block.TopReference(0)) ? 1 : 0) : 3;
      } else if (block.leftAvail[0]) {
         return block.IsLeftSingle() ? (isBackwardReference(block.LeftReference(0)) ? 1 : 0) : 3;
      } else {
         return 1;
      }
   }

   private int getReferenceContext(Block block, int type0Start, int type0End, int type1Start, int type1End) {
      int count0 = 0;
      int count1 = 0;

      for (int type = type0Start; type <= type0End; type++) {
         count0 += block.CountReferences(type);
      }

      for (int type = type1Start; type <= type1End; type++) {
         count1 += block.CountReferences(type);
      }

      return count0 < count1 ? 0 : (count0 == count1 ? 1 : 2);
   }

   private boolean readSegmentId(Block block) {
      D.SegmentationMap map = this.current_frame_.segmentation_map();
      int top_left = -1;
      if (block.topAvail[0] && block.leftAvail[0]) {
         top_left = map.segment_id(block.row4x4 - 1, block.column4x4 - 1);
      }

      int top = -1;
      if (block.topAvail[0]) {
         top = map.segment_id(block.row4x4 - 1, block.column4x4);
      }

      int left = -1;
      if (block.leftAvail[0]) {
         left = map.segment_id(block.row4x4, block.column4x4 - 1);
      }

      int pred;
      if (top == -1) {
         pred = left == -1 ? 0 : left;
      } else if (left == -1) {
         pred = top;
      } else {
         pred = top_left == top ? top : left;
      }

      D.BlockParams bp = block.bp;
      if (bp.skip) {
         bp.prediction_parameters.segment_id = pred;
         return true;
      } else {
         int context = 0;
         if (top_left < 0) {
            context = 0;
         } else if (top_left == top && top_left == left) {
            context = 2;
         } else if (top_left == top || top_left == left || top == left) {
            context = 1;
         }

         int[] segment_id_cdf = this.symbol_decoder_context_.segment_id_cdf[context];
         int encoded_segment_id = this.reader_.readSymbol(segment_id_cdf, 8);
         bp.prediction_parameters.segment_id = decodeSegmentId(encoded_segment_id, pred, this.frame_header_.segmentation.last_active_segment_id + 1);
         if (bp.prediction_parameters.segment_id >= 0 && bp.prediction_parameters.segment_id <= this.frame_header_.segmentation.last_active_segment_id) {
            return true;
         } else {
            LogWriter.writeLog("Read Segment id error: corrupted ids");
            return false;
         }
      }
   }

   private boolean readIntraSegmentId(Block block) {
      D.BlockParams bp = block.bp;
      if (!this.frame_header_.segmentation.enabled) {
         bp.prediction_parameters.segment_id = 0;
         return true;
      } else {
         return this.readSegmentId(block);
      }
   }

   private void readSkip(Block block) {
      D.BlockParams bp = block.bp;
      if (this.frame_header_.segmentation.segment_id_pre_skip && this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 6)) {
         bp.skip = true;
      } else {
         int context = 0;
         if (block.topAvail[0] && block.bp_top.skip) {
            context++;
         }

         if (block.leftAvail[0] && block.bp_left.skip) {
            context++;
         }

         int[] skip_cdf = this.symbol_decoder_context_.skip_cdf[context];
         bp.skip = this.reader_.readSymbol(skip_cdf);
      }
   }

   private boolean readSkipMode(Block block) {
      LogWriter.writeLog("reading skip mode is called");
      D.BlockParams bp = block.bp;
      if (this.frame_header_.skip_mode_present
         && !this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 6)
         && !this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 5)
         && !this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 7)
         && !D.IsBlockDimension4(block.size)) {
         int context = (block.leftAvail[0] ? (this.left_context_.skip_mode[block.left_context_index] ? 1 : 0) : 0)
            + (block.topAvail[0] ? (block.top_context.skip_mode[block.top_context_index] ? 1 : 0) : 0);
         return this.reader_.readSymbol(this.symbol_decoder_context_.skip_mode_cdf[context]);
      } else {
         return false;
      }
   }

   private void readCdef(Block block) {
      D.BlockParams bp = block.bp;
      if (!bp.skip
         && !this.frame_header_.coded_lossless
         && this.sequence_header_.enable_cdef
         && !this.frame_header_.allow_intrabc
         && this.frame_header_.cdef.bits != 0) {
         int[] cdef_index = this.cdef_index_.data_;
         int cdef_indexPos = block.row4x4 / 16 * this.cdef_index_.columns() + block.column4x4 / 16;
         int stride = this.cdef_index_.columns();
         if (cdef_index[cdef_indexPos + 0] == -1) {
            cdef_index[cdef_indexPos + 0] = (byte)this.reader_.readLiteral(this.frame_header_.cdef.bits);
            if (block.size == 21) {
               cdef_index[cdef_indexPos + 1] = cdef_index[cdef_indexPos + 0];
               cdef_index[cdef_indexPos + stride] = cdef_index[cdef_indexPos + 0];
               cdef_index[cdef_indexPos + stride + 1] = cdef_index[cdef_indexPos + 0];
            } else if (block.width4x4 > 16) {
               cdef_index[cdef_indexPos + 1] = cdef_index[cdef_indexPos + 0];
            } else if (block.height4x4 > 16) {
               cdef_index[cdef_indexPos + stride] = cdef_index[cdef_indexPos + 0];
            }
         }
      }
   }

   private int readAndClipDelta(int[] cdf, int delta_small, int scale, int min_value, int max_value, int value) {
      int abs = this.reader_.readSymbol(cdf, 4);
      if (abs == delta_small) {
         int remaining_bit_count = (int)this.reader_.readLiteral(3) + 1;
         int abs_remaining_bits = (int)this.reader_.readLiteral(remaining_bit_count);
         abs = abs_remaining_bits + (1 << remaining_bit_count) + 1;
      }

      if (abs != 0) {
         boolean sign = this.reader_.readBit() != 0;
         int scaled_abs = abs << scale;
         int reduced_delta = sign ? -scaled_abs : scaled_abs;
         value += reduced_delta;
         value = D.Clip3(value, min_value, max_value);
      }

      return value;
   }

   private void readQuantizerIndexDelta(Block block) {
      D.BlockParams bp = block.bp;
      if (block.size != this.SuperBlockSize() || !bp.skip) {
         this.current_quantizer_index_ = this.readAndClipDelta(
            this.symbol_decoder_context_.delta_q_cdf, 3, this.frame_header_.delta_q.scale, 1, 255, this.current_quantizer_index_
         );
      }
   }

   private void readLoopFilterDelta(Block block) {
      D.BlockParams bp = block.bp;
      if (this.frame_header_.delta_lf.present && (block.size != this.SuperBlockSize() || !bp.skip)) {
         int frame_lf_count = 1;
         if (this.frame_header_.delta_lf.multi) {
            frame_lf_count = 4 - (this.PlaneCount() > 1 ? 0 : 2);
         }

         boolean recompute_deblock_filter_levels = false;

         for (int i = 0; i < frame_lf_count; i++) {
            int[] delta_lf_abs_cdf = this.frame_header_.delta_lf.multi
               ? this.symbol_decoder_context_.delta_lf_multi_cdf[i]
               : this.symbol_decoder_context_.delta_lf_cdf;
            int old_delta_lf = this.delta_lf_[i];
            this.delta_lf_[i] = this.readAndClipDelta(delta_lf_abs_cdf, 3, this.frame_header_.delta_lf.scale, -63, 63, this.delta_lf_[i]);
            recompute_deblock_filter_levels = recompute_deblock_filter_levels || old_delta_lf != this.delta_lf_[i];
         }

         this.delta_lf_all_zero_ = (this.delta_lf_[0] | this.delta_lf_[1] | this.delta_lf_[2] | this.delta_lf_[3]) == 0;
         if (!this.delta_lf_all_zero_ && recompute_deblock_filter_levels) {
            this.post_filter_.ComputeDeblockFilterLevels(this.delta_lf_, this.deblock_filter_levels_);
         }
      }
   }

   private void readPredictionModeY(Block block, boolean intraYMode) {
      int[] cdf;
      if (intraYMode) {
         int top_mode = block.topAvail[0] ? block.bp_top.y_mode : 0;
         int left_mode = block.leftAvail[0] ? block.bp_left.y_mode : 0;
         int top_context = D.kIntraYModeContext[top_mode];
         int left_context = D.kIntraYModeContext[left_mode];
         cdf = this.symbol_decoder_context_.intra_frame_y_mode_cdf[top_context][left_context];
      } else {
         cdf = this.symbol_decoder_context_.y_mode_cdf[D.kSizeGroup[block.size]];
      }

      block.bp.y_mode = this.reader_.readSymbol(cdf, 13);
   }

   private void readIntraAngleInfo(Block block, int plane_type) {
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      prediction_parameters.angle_delta[plane_type] = 0;
      int mode = plane_type == 0 ? bp.y_mode : bp.prediction_parameters.uv_mode;
      if (!D.IsBlockSmallerThan8x8(block.size) && D.IsDirectionalMode(mode)) {
         int[] cdf = this.symbol_decoder_context_.angle_delta_cdf[mode - 1];
         prediction_parameters.angle_delta[plane_type] = this.reader_.readSymbol(cdf, 7);
         prediction_parameters.angle_delta[plane_type] = prediction_parameters.angle_delta[plane_type] - 3;
      }
   }

   private void readCflAlpha(Block block) {
      int signs = this.reader_.readSymbol(this.symbol_decoder_context_.cfl_alpha_signs_cdf, 8);
      int[] cfl_lookup = D.kCflAlphaLookup[signs];
      int sign_u = cfl_lookup[0];
      int sign_v = cfl_lookup[1];
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      prediction_parameters.cfl_alpha_u = 0;
      if (sign_u != 0) {
         prediction_parameters.cfl_alpha_u = this.reader_.readSymbol(this.symbol_decoder_context_.cfl_alpha_cdf[cfl_lookup[2]], 16) + 1;
         if (sign_u == 1) {
            prediction_parameters.cfl_alpha_u *= -1;
         }
      }

      prediction_parameters.cfl_alpha_v = 0;
      if (sign_v != 0) {
         prediction_parameters.cfl_alpha_v = this.reader_.readSymbol(this.symbol_decoder_context_.cfl_alpha_cdf[cfl_lookup[3]], 16) + 1;
         if (sign_v == 1) {
            prediction_parameters.cfl_alpha_v *= -1;
         }
      }
   }

   private void readPredictionModeUV(Block block) {
      D.BlockParams bp = block.bp;
      boolean chroma_from_luma_allowed;
      if (this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id]) {
         chroma_from_luma_allowed = block.residual_size[1] == 0;
      } else {
         chroma_from_luma_allowed = isBlockDimensionLessThan64(block.size);
      }

      int[] cdf = this.symbol_decoder_context_.uv_mode_cdf[chroma_from_luma_allowed ? 1 : 0][bp.y_mode];
      if (chroma_from_luma_allowed) {
         bp.prediction_parameters.uv_mode = this.reader_.readSymbol(cdf, 14);
      } else {
         bp.prediction_parameters.uv_mode = this.reader_.readSymbol(cdf, 13);
      }
   }

   private int readMotionVectorComponent(Block block, int component) {
      int context = block.bp.prediction_parameters.use_intra_block_copy ? 1 : 0;
      boolean sign = this.reader_.readSymbol(this.symbol_decoder_context_.mv_sign_cdf[component][context]);
      int mv_class = this.reader_.readSymbol(this.symbol_decoder_context_.mv_class_cdf[component][context], 11);
      int magnitude = 1;
      int value;
      int[] fraction_cdf;
      int[] precision_cdf;
      if (mv_class == 0) {
         value = this.reader_.readSymbol(this.symbol_decoder_context_.mv_class0_bit_cdf[component][context]) ? 1 : 0;
         fraction_cdf = this.symbol_decoder_context_.mv_class0_fraction_cdf[component][context][value];
         precision_cdf = this.symbol_decoder_context_.mv_class0_high_precision_cdf[component][context];
      } else {
         value = 0;

         for (int i = 0; i < mv_class; i++) {
            int bit = this.reader_.readSymbol(this.symbol_decoder_context_.mv_bit_cdf[component][context][i]) ? 1 : 0;
            value |= bit << i;
         }

         magnitude += 2 << mv_class + 2;
         fraction_cdf = this.symbol_decoder_context_.mv_fraction_cdf[component][context];
         precision_cdf = this.symbol_decoder_context_.mv_high_precision_cdf[component][context];
      }

      int fraction = this.frame_header_.force_integer_mv == 0 ? this.reader_.readSymbol(fraction_cdf, 4) : 3;
      int precision = this.frame_header_.allow_high_precision_mv ? (this.reader_.readSymbol(precision_cdf) ? 1 : 0) : 1;
      magnitude += value << 3 | fraction << 1 | precision;
      return sign ? -magnitude : magnitude;
   }

   private void ReadMotionVector(Block block, int index) {
      D.BlockParams bp = block.bp;
      int context = block.bp.prediction_parameters.use_intra_block_copy ? 1 : 0;
      int mv_joint = this.reader_.readSymbol(this.symbol_decoder_context_.mv_joint_cdf[context], 4);
      if (mv_joint == 2 || mv_joint == 3) {
         bp.mv.mv[index].mv[0] = this.readMotionVectorComponent(block, 0);
      }

      if (mv_joint == 1 || mv_joint == 3) {
         bp.mv.mv[index].mv[1] = this.readMotionVectorComponent(block, 1);
      }
   }

   private void readFilterIntraModeInfo(Block block) {
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      prediction_parameters.use_filter_intra = false;
      if (this.sequence_header_.enable_filter_intra
         && bp.y_mode == 0
         && bp.prediction_parameters.palette_mode_info.size[0] == 0
         && isBlockDimensionLessThan64(block.size)) {
         prediction_parameters.use_filter_intra = this.reader_.readSymbol(this.symbol_decoder_context_.use_filter_intra_cdf[block.size]);
         if (prediction_parameters.use_filter_intra) {
            prediction_parameters.filter_intra_mode = this.reader_.readSymbol(this.symbol_decoder_context_.filter_intra_mode_cdf, 5);
         }
      }
   }

   private boolean decodeIntraModeInfo(Block block) {
      D.BlockParams bp = block.bp;
      bp.skip = false;
      if (this.frame_header_.segmentation.segment_id_pre_skip && !this.readIntraSegmentId(block)) {
         LogWriter.writeLog("Tile: Error in DecodeIntraModeInfo");
         return false;
      } else {
         this.setCdfContextSkipMode(block, false);
         this.readSkip(block);
         if (!this.frame_header_.segmentation.segment_id_pre_skip && !this.readIntraSegmentId(block)) {
            LogWriter.writeLog("Tile: Error in DecodeIntraModeInfo2");
            return false;
         } else {
            this.readCdef(block);
            if (this.read_deltas_) {
               this.readQuantizerIndexDelta(block);
               this.readLoopFilterDelta(block);
               this.read_deltas_ = false;
            }

            D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
            prediction_parameters.use_intra_block_copy = false;
            if (this.frame_header_.allow_intrabc) {
               prediction_parameters.use_intra_block_copy = this.reader_.readSymbol(this.symbol_decoder_context_.intra_block_copy_cdf);
            }

            if (prediction_parameters.use_intra_block_copy) {
               bp.is_inter = true;
               bp.reference_frame[0] = 0;
               bp.reference_frame[1] = -1;
               bp.y_mode = 0;
               bp.prediction_parameters.uv_mode = 0;
               this.setCdfContextUVMode(block);
               prediction_parameters.motion_mode = 0;
               prediction_parameters.compound_prediction_type = 2;
               bp.prediction_parameters.palette_mode_info.size[0] = 0;
               bp.prediction_parameters.palette_mode_info.size[1] = 0;
               this.setCdfContextPaletteSize(block);
               bp.interpolation_filter[0] = 3;
               bp.interpolation_filter[1] = 3;
               D.MvContexts dummy_mode_contexts = new D.MvContexts();
               this.findMvStack(block, false, dummy_mode_contexts);
               return this.assignIntraMv(block);
            } else {
               bp.is_inter = false;
               return this.readIntraBlockModeInfo(block, true);
            }
         }
      }
   }

   private void findMvStack(Block block, boolean is_compound, D.MvContexts contexts) {
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      setupGlobalMv(block, 0, prediction_parameters.global_mv[0]);
      if (is_compound) {
         setupGlobalMv(block, 1, prediction_parameters.global_mv[1]);
      }

      boolean[] found_new_mv = new boolean[]{false};
      boolean[] found_row_match = new boolean[]{false};
      int[] num_mv_found = new int[]{0};
      scanRow(block, block.column4x4, -1, is_compound, found_new_mv, found_row_match, num_mv_found);
      boolean[] found_column_match = new boolean[]{false};
      scanColumn(block, block.row4x4, -1, is_compound, found_new_mv, found_column_match, num_mv_found);
      if (Math.max(block.width4x4, block.height4x4) <= 16) {
         scanPoint(block, -1, block.width4x4, is_compound, found_new_mv, found_row_match, num_mv_found);
      }

      int nearest_matches = (found_row_match[0] ? 1 : 0) + (found_column_match[0] ? 1 : 0);
      prediction_parameters.nearest_mv_count = num_mv_found[0];
      if (block.tile.frame_header().use_ref_frame_mvs) {
         LogWriter.writeLog("Tile: temporal scan not implemented");
      } else {
         contexts.zero_mv = 0;
      }

      boolean[] dummy_bool = new boolean[]{false};
      scanPoint(block, -1, -1, is_compound, dummy_bool, found_row_match, num_mv_found);
      int[] deltas = new int[]{-3, -5};

      for (int i = 0; i < 2; i++) {
         if (i == 0 || block.height4x4 > 1) {
            scanRow(block, block.column4x4 | 1, deltas[i] + (block.row4x4 & 1), is_compound, dummy_bool, found_row_match, num_mv_found);
         }

         if (i == 0 || block.width4x4 > 1) {
            scanColumn(block, block.row4x4 | 1, deltas[i] + (block.column4x4 & 1), is_compound, dummy_bool, found_column_match, num_mv_found);
         }
      }

      if (num_mv_found[0] < 2) {
         this.extraSearch(block, is_compound, num_mv_found);
      } else {
         LogWriter.writeLog("Tile : Find MV SortWeightIndexStack not implemented");
      }

      prediction_parameters.ref_mv_count = num_mv_found[0];
      int total_matches = (found_row_match[0] ? 1 : 0) + (found_column_match[0] ? 1 : 0);
      computeContexts(found_new_mv, nearest_matches, total_matches, contexts.new_mv, contexts.reference_mv);
   }

   private static int arrayMin(int... elems) {
      int v = Integer.MAX_VALUE;

      for (int x : elems) {
         v = Math.min(x, v);
      }

      return v;
   }

   private void addExtraSingleMvCandidate(Block block, int mvRow, int mvColumn, int[] numMvFound) {
      D.BlockParams bp = block.tile.Parameters(mvRow, mvColumn);
      boolean[] reference_frame_sign_bias = block.tile.reference_frame_sign_bias_;
      int block_reference_frame = block.bp.reference_frame[0];
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      D.MotionVector[] ref_mv_stack = prediction_parameters.ref_mv_stack;
      int num_found = numMvFound[0];

      for (int i = 0; i < 2; i++) {
         int candidate_reference_frame = bp.reference_frame[i];
         if (candidate_reference_frame > 0) {
            D.MotionVector candidate_mv = bp.mv.mv[i];
            if (reference_frame_sign_bias[candidate_reference_frame] != reference_frame_sign_bias[block_reference_frame]) {
               candidate_mv.mv[0] = candidate_mv.mv[0] * -1;
               candidate_mv.mv[1] = candidate_mv.mv[1] * -1;
            }

            if ((num_found == 0 || ref_mv_stack[0].mv32 != candidate_mv.mv32) && (num_found != 2 || ref_mv_stack[1].mv32 != candidate_mv.mv32)) {
               ref_mv_stack[num_found] = candidate_mv;
               prediction_parameters.SetWeightIndexStackEntry(num_found, 0);
               num_found++;
            }
         }
      }

      numMvFound[0] = num_found;
   }

   private void extraSearch(Block block, boolean isCompound, int[] numMvFound) {
      Tile tile = block.tile;
      int num4x4 = arrayMin(block.width4x4, tile.frame_header().columns4x4 - block.column4x4, block.height4x4, tile.frame_header().rows4x4 - block.row4x4, 16);
      int[] refIdCount = new int[]{0, 0};
      D.MotionVector[][] ref_id = new D.MotionVector[][]{{new D.MotionVector(), new D.MotionVector()}, {new D.MotionVector(), new D.MotionVector()}};
      int[] ref_diff_count = new int[]{0, 0};
      D.MotionVector[][] ref_diff = new D.MotionVector[][]{{new D.MotionVector(), new D.MotionVector()}, {new D.MotionVector(), new D.MotionVector()}};
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;

      for (int pass = 0; pass < 2 && numMvFound[0] < 2; pass++) {
         int i = 0;

         while (i < num4x4) {
            int mv_row = block.row4x4 + (pass == 0 ? -1 : i);
            int mv_column = block.column4x4 + (pass == 0 ? i : -1);
            if (!tile.IsTopLeftInside(mv_row + 1, mv_column + 1)) {
               break;
            }

            if (isCompound) {
               addExtraCompoundMvCandidate(block, mv_row, mv_column, refIdCount, ref_id, ref_diff_count, ref_diff);
            } else {
               this.addExtraSingleMvCandidate(block, mv_row, mv_column, numMvFound);
               if (numMvFound[0] >= 2) {
                  break;
               }
            }

            D.BlockParams bp = tile.Parameters(mv_row, mv_column);
            i += pass == 0 ? D.kNum4x4BlocksWide[bp.size] : D.kNum4x4BlocksHigh[bp.size];
         }
      }

      if (isCompound) {
         D.CompoundMotionVector[] compound_ref_mv_stack = prediction_parameters.compound_ref_mv_stack;
         D.CompoundMotionVector[] combined_mvs = new D.CompoundMotionVector[]{new D.CompoundMotionVector(), new D.CompoundMotionVector()};

         for (int i = 0; i < 2; i++) {
            int count = 0;

            for (int j = 0; j < refIdCount[i]; count++) {
               combined_mvs[count].mv[i] = ref_id[i][j];
               j++;
            }

            for (int j = 0; j < ref_diff_count[i] && count < 2; count++) {
               combined_mvs[count].mv[i] = ref_diff[i][j];
               j++;
            }

            while (count < 2) {
               combined_mvs[count].mv[i] = prediction_parameters.global_mv[i];
               count++;
            }
         }

         if (numMvFound[0] == 1) {
            if (combined_mvs[0].mv64 == compound_ref_mv_stack[0].mv64) {
               compound_ref_mv_stack[1].mv64 = combined_mvs[1].mv64;
            } else {
               compound_ref_mv_stack[1].mv64 = combined_mvs[0].mv64;
            }

            prediction_parameters.SetWeightIndexStackEntry(1, 0);
         } else {
            for (int i = 0; i < 2; i++) {
               compound_ref_mv_stack[i].mv64 = combined_mvs[i].mv64;
               prediction_parameters.SetWeightIndexStackEntry(i, 0);
            }
         }

         numMvFound[0] = 2;
      } else {
         D.MotionVector[] ref_mv_stack = prediction_parameters.ref_mv_stack;

         for (int i = numMvFound[0]; i < 2; i++) {
            ref_mv_stack[i] = prediction_parameters.global_mv[0];
            prediction_parameters.SetWeightIndexStackEntry(i, 0);
         }
      }
   }

   private static void computeContexts(boolean[] found_new_mv, int nearest_matches, int total_matches, int[] new_mv_context, int[] reference_mv_context) {
      switch (nearest_matches) {
         case 0:
            new_mv_context[0] = Math.min(total_matches, 1);
            reference_mv_context[0] = total_matches;
            break;
         case 1:
            new_mv_context[0] = 3 - (found_new_mv[0] ? 1 : 0);
            reference_mv_context[0] = 2 + total_matches;
            break;
         default:
            new_mv_context[0] = 5 - (found_new_mv[0] ? 1 : 0);
            reference_mv_context[0] = 5;
      }
   }

   private int computePredictedSegmentId(Block block) {
      if (this.prev_segment_ids_ == null) {
         return 0;
      } else {
         int x_limit = Math.min(this.frame_header_.columns4x4 - block.column4x4, block.width4x4);
         int y_limit = Math.min(this.frame_header_.rows4x4 - block.row4x4, block.height4x4);
         int id = 7;

         for (int y = 0; y < y_limit; y++) {
            for (int x = 0; x < x_limit; x++) {
               int prev_segment_id = this.prev_segment_ids_.segment_id(block.row4x4 + y, block.column4x4 + x);
               id = Math.min(id, prev_segment_id);
            }
         }

         return id;
      }
   }

   private void setCdfContextUsePredictedSegmentId(Block block, boolean use_predicted_segment_id) {
      Mem.set(this.left_context_.use_predicted_segment_id, block.left_context_index, use_predicted_segment_id, block.height4x4);
      Mem.set(block.top_context.use_predicted_segment_id, block.top_context_index, use_predicted_segment_id, block.width4x4);
   }

   private boolean readInterSegmentId(Block block, boolean preSkip) {
      LogWriter.writeLog("This method should not be called in intra prediction");
      D.BlockParams bp = block.bp;
      if (!this.frame_header_.segmentation.enabled) {
         bp.prediction_parameters.segment_id = 0;
         return true;
      } else if (!this.frame_header_.segmentation.update_map) {
         bp.prediction_parameters.segment_id = this.computePredictedSegmentId(block);
         return true;
      } else {
         if (preSkip) {
            if (!this.frame_header_.segmentation.segment_id_pre_skip) {
               bp.prediction_parameters.segment_id = 0;
               return true;
            }
         } else if (bp.skip) {
            this.setCdfContextUsePredictedSegmentId(block, false);
            return this.readSegmentId(block);
         }

         if (this.frame_header_.segmentation.temporal_update) {
            int context = (block.leftAvail[0] ? (this.left_context_.use_predicted_segment_id[block.left_context_index] ? 1 : 0) : 0)
               + (block.topAvail[0] ? (block.top_context.use_predicted_segment_id[block.top_context_index] ? 1 : 0) : 0);
            boolean use_predicted_segment_id = this.reader_.readSymbol(this.symbol_decoder_context_.use_predicted_segment_id_cdf[context]);
            this.setCdfContextUsePredictedSegmentId(block, use_predicted_segment_id);
            if (use_predicted_segment_id) {
               bp.prediction_parameters.segment_id = this.computePredictedSegmentId(block);
               return true;
            }
         }

         return this.readSegmentId(block);
      }
   }

   private void readIsInter(Block block, boolean skip_mode) {
      LogWriter.writeLog("Read Is inter is called");
      D.BlockParams bp = block.bp;
      if (skip_mode) {
         bp.is_inter = true;
      } else {
         LogWriter.writeLog("Tile: Read Is Inter not supported");
         bp.is_inter = false;
      }
   }

   private void setCdfContextPaletteSize(Block block) {
      D.PaletteModeInfo palette_mode_info = block.bp.prediction_parameters.palette_mode_info;

      for (int plane_type = 0; plane_type <= 1; plane_type++) {
         Mem.set(this.left_context_.palette_size[plane_type], block.left_context_index, palette_mode_info.size[plane_type], block.height4x4);
         Mem.set(block.top_context.palette_size[plane_type], block.top_context_index, palette_mode_info.size[plane_type], block.width4x4);
         if (palette_mode_info.size[plane_type] != 0) {
            for (int i = block.left_context_index; i < block.left_context_index + block.height4x4; i++) {
               Mem.cpy(this.left_context_.palette_color[i][plane_type], palette_mode_info.color[plane_type], 8);
            }

            for (int i = block.top_context_index; i < block.top_context_index + block.width4x4; i++) {
               Mem.cpy(block.top_context.palette_color[i][plane_type], palette_mode_info.color[plane_type], 8);
            }
         }
      }
   }

   private void setCdfContextUVMode(Block block) {
      if (this.subsampling_x_[1] == 0 || (block.column4x4 & 1) == 1 || block.width4x4 > 1) {
         Mem.set(this.left_context_.uv_mode, block.left_context_index, block.bp.prediction_parameters.uv_mode, block.height4x4);
      }

      if (this.subsampling_y_[1] == 0 || (block.row4x4 & 1) == 1 || block.height4x4 > 1) {
         Mem.set(block.top_context.uv_mode, block.top_context_index, block.bp.prediction_parameters.uv_mode, block.width4x4);
      }
   }

   private boolean readIntraBlockModeInfo(Block block, boolean intra_y_mode) {
      D.BlockParams bp = block.bp;
      bp.reference_frame[0] = 0;
      bp.reference_frame[1] = -1;
      this.readPredictionModeY(block, intra_y_mode);
      this.readIntraAngleInfo(block, 0);
      if (block.HasChroma()) {
         this.readPredictionModeUV(block);
         if (bp.prediction_parameters.uv_mode == 13) {
            this.readCflAlpha(block);
         }

         if (block.leftAvail[1]) {
            int smooth_row = block.row4x4 + (~block.row4x4 & this.subsampling_y_[1]);
            int smooth_column = block.column4x4 - 1 - (block.column4x4 & this.subsampling_x_[1]);
            D.BlockParams bp_left = this.block_parameters_holder_.Find(smooth_row, smooth_column);
            bp.prediction_parameters.chroma_left_uses_smooth_prediction = bp_left.reference_frame[0] <= 0
               && D.kPredictionModeSmoothMask.Contains(this.left_context_.uv_mode[this.CdfContextIndex(smooth_row)]);
         }

         if (block.topAvail[1]) {
            int smooth_row = block.row4x4 - 1 - (block.row4x4 & this.subsampling_y_[1]);
            int smooth_column = block.column4x4 + (~block.column4x4 & this.subsampling_x_[1]);
            D.BlockParams bp_top = this.block_parameters_holder_.Find(smooth_row, smooth_column);
            bp.prediction_parameters.chroma_top_uses_smooth_prediction = bp_top.reference_frame[0] <= 0
               && D.kPredictionModeSmoothMask
                  .Contains(this.top_context_.get()[this.SuperBlockColumnIndex(smooth_column)].uv_mode[this.CdfContextIndex(smooth_column)]);
         }

         this.setCdfContextUVMode(block);
         this.readIntraAngleInfo(block, 1);
      }

      this.readPaletteModeInfo(block);
      this.setCdfContextPaletteSize(block);
      this.readFilterIntraModeInfo(block);
      return true;
   }

   private int readCompoundReferenceType(Block block) {
      boolean top_comp_inter = block.topAvail[0] && !block.IsTopIntra() && !block.IsTopSingle();
      boolean left_comp_inter = block.leftAvail[0] && !block.IsLeftIntra() && !block.IsLeftSingle();
      boolean top_uni_comp = top_comp_inter && isSameDirectionReferencePair(block.TopReference(0), block.TopReference(1));
      boolean left_uni_comp = left_comp_inter && isSameDirectionReferencePair(block.LeftReference(0), block.LeftReference(1));
      int context;
      if (block.topAvail[0] && !block.IsTopIntra() && block.leftAvail[0] && !block.IsLeftIntra()) {
         int same_direction = isSameDirectionReferencePair(block.TopReference(0), block.LeftReference(0)) ? 1 : 0;
         if (!top_comp_inter && !left_comp_inter) {
            context = 1 + same_direction * 2;
         } else if (!top_comp_inter) {
            context = left_uni_comp ? 3 + same_direction : 1;
         } else if (!left_comp_inter) {
            context = top_uni_comp ? 3 + same_direction : 1;
         } else if (!top_uni_comp && !left_uni_comp) {
            context = 0;
         } else if (top_uni_comp && left_uni_comp) {
            context = 3 + (block.TopReference(0) == 5 == (block.LeftReference(0) == 5) ? 1 : 0);
         } else {
            context = 2;
         }
      } else if (block.topAvail[0] && block.leftAvail[0]) {
         if (top_comp_inter) {
            context = 1 + (top_uni_comp ? 1 : 0) * 2;
         } else if (left_comp_inter) {
            context = 1 + (left_uni_comp ? 1 : 0) * 2;
         } else {
            context = 2;
         }
      } else if (top_comp_inter) {
         context = (top_uni_comp ? 1 : 0) * 4;
      } else if (left_comp_inter) {
         context = (left_uni_comp ? 1 : 0) * 4;
      } else {
         context = 2;
      }

      return this.reader_.readSymbol(this.symbol_decoder_context_.compound_reference_type_cdf[context]) ? 1 : 0;
   }

   private int[] getReferenceCdf(Block block, int type, boolean isSingle, boolean isBackward, int index) {
      int context = 0;
      if ((type != 0 || index != 0) && (!isSingle || index != 1)) {
         if (type == 0 && index == 1) {
            context = this.getReferenceContext(block, 2, 2, 3, 4);
         } else if ((type != 0 || index != 2) && (type != 1 || index != 2) && (!isSingle || index != 5)) {
            if ((type != 1 || index != 0) && (!isSingle || index != 3)) {
               if ((type != 1 || index != 1) && (!isSingle || index != 4)) {
                  if ((!isSingle || index != 2) && (!isBackward || index != 0)) {
                     if (isSingle && index == 6 || isBackward && index == 1) {
                        context = this.getReferenceContext(block, 5, 5, 6, 6);
                     }
                  } else {
                     context = this.getReferenceContext(block, 5, 6, 7, 7);
                  }
               } else {
                  context = this.getReferenceContext(block, 1, 1, 2, 2);
               }
            } else {
               context = this.getReferenceContext(block, 1, 2, 3, 4);
            }
         } else {
            context = this.getReferenceContext(block, 3, 3, 4, 4);
         }
      } else {
         context = this.getReferenceContext(block, 1, 4, 5, 7);
      }

      if (isSingle) {
         return this.symbol_decoder_context_.single_reference_cdf[context][index - 1];
      } else {
         return isBackward
            ? this.symbol_decoder_context_.compound_backward_reference_cdf[context][index]
            : this.symbol_decoder_context_.compound_reference_cdf[type][context][index];
      }
   }

   private void readReferenceFrames(Block block, boolean skip_mode) {
      D.BlockParams bp = block.bp;
      if (skip_mode) {
         bp.reference_frame[0] = this.frame_header_.skip_mode_frame[0];
         bp.reference_frame[1] = this.frame_header_.skip_mode_frame[1];
      } else if (this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 5)) {
         bp.reference_frame[0] = this.frame_header_.segmentation.feature_data[bp.prediction_parameters.segment_id][5];
         bp.reference_frame[1] = -1;
      } else if (!this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 6)
         && !this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 7)) {
         boolean use_compound_reference = this.frame_header_.reference_mode_select
            && Math.min(block.width4x4, block.height4x4) >= 2
            && this.reader_.readSymbol(this.symbol_decoder_context_.use_compound_reference_cdf[getUseCompoundReferenceContext(block)]);
         if (use_compound_reference) {
            int reference_type = this.readCompoundReferenceType(block);
            if (reference_type == 0) {
               if (this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 0))) {
                  bp.reference_frame[0] = 5;
                  bp.reference_frame[1] = 7;
               } else if (!this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 1))) {
                  bp.reference_frame[0] = 1;
                  bp.reference_frame[1] = 2;
               } else if (this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 2))) {
                  bp.reference_frame[0] = 1;
                  bp.reference_frame[1] = 4;
               } else {
                  bp.reference_frame[0] = 1;
                  bp.reference_frame[1] = 3;
               }
            } else {
               if (this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 0))) {
                  bp.reference_frame[0] = this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 2)) ? 4 : 3;
               } else {
                  bp.reference_frame[0] = this.reader_.readSymbol(this.getReferenceCdf(block, reference_type, false, false, 1)) ? 2 : 1;
               }

               if (this.reader_.readSymbol(this.getReferenceCdf(block, 2, false, true, 0))) {
                  bp.reference_frame[1] = 7;
               } else {
                  bp.reference_frame[1] = this.reader_.readSymbol(this.getReferenceCdf(block, 2, false, true, 1)) ? 6 : 5;
               }
            }
         } else {
            bp.reference_frame[1] = -1;
            if (this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 1))) {
               if (this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 2))) {
                  bp.reference_frame[0] = 7;
               } else {
                  bp.reference_frame[0] = this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 6)) ? 6 : 5;
               }
            } else if (this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 3))) {
               bp.reference_frame[0] = this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 5)) ? 4 : 3;
            } else {
               bp.reference_frame[0] = this.reader_.readSymbol(this.getReferenceCdf(block, 2, true, false, 4)) ? 2 : 1;
            }
         }
      } else {
         bp.reference_frame[0] = 1;
         bp.reference_frame[1] = -1;
      }
   }

   private void readInterPredictionModeY(Block block, D.MvContexts mvContexts, boolean skipMode) {
      D.BlockParams bp = block.bp;
      if (skipMode) {
         bp.y_mode = 18;
      } else if (this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 6)
         || this.frame_header_.segmentation.FeatureActive(bp.prediction_parameters.segment_id, 7)) {
         bp.y_mode = 16;
      } else if (bp.reference_frame[1] > 0) {
         int idx0 = mvContexts.reference_mv[0] >> 1;
         int idx1 = Math.min(mvContexts.new_mv[0], 4);
         int context = D.kCompoundModeContextMap[idx0][idx1];
         int offset = this.reader_.readSymbol(this.symbol_decoder_context_.compound_prediction_mode_cdf[context], 8);
         bp.y_mode = 18 + offset;
      } else if (!this.reader_.readSymbol(this.symbol_decoder_context_.new_mv_cdf[mvContexts.new_mv[0]])) {
         bp.y_mode = 17;
      } else if (!this.reader_.readSymbol(this.symbol_decoder_context_.zero_mv_cdf[mvContexts.zero_mv])) {
         bp.y_mode = 16;
      } else {
         bp.y_mode = this.reader_.readSymbol(this.symbol_decoder_context_.reference_mv_cdf[mvContexts.reference_mv[0]]) ? 15 : 14;
      }
   }

   private void readRefMvIndex(Block block) {
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      prediction_parameters.ref_mv_index = 0;
      if (bp.y_mode == 17 || bp.y_mode == 25 || D.kPredictionModeHasNearMvMask.Contains(bp.y_mode)) {
         int start = D.kPredictionModeHasNearMvMask.Contains(bp.y_mode) ? 1 : 0;
         prediction_parameters.ref_mv_index = start;

         for (int i = start; i < start + 2 && prediction_parameters.ref_mv_count > i + 1; i++) {
            boolean ref_mv_index_bit = this.reader_
               .readSymbol(this.symbol_decoder_context_.ref_mv_index_cdf[getRefMvIndexContext(prediction_parameters.nearest_mv_count, i)]);
            prediction_parameters.ref_mv_index = i + (ref_mv_index_bit ? 1 : 0);
            if (!ref_mv_index_bit) {
               return;
            }
         }
      }
   }

   private void readInterIntraMode(Block block, boolean isCompound, boolean skipMode) {
      LogWriter.writeLog("ReadInterIntraMode not to be called");
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      prediction_parameters.inter_intra_mode = 4;
      prediction_parameters.is_wedge_inter_intra = false;
      if (!skipMode && this.sequence_header_.enable_interintra_compound && !isCompound && D.kIsInterIntraModeAllowedMask.Contains(block.size)) {
         if (!this.reader_.readSymbol(this.symbol_decoder_context_.is_inter_intra_cdf[D.kSizeGroup[block.size] - 1])) {
            prediction_parameters.inter_intra_mode = 4;
         } else {
            prediction_parameters.inter_intra_mode = this.reader_
               .readSymbol(this.symbol_decoder_context_.inter_intra_mode_cdf[D.kSizeGroup[block.size] - 1], 4);
            bp.reference_frame[1] = 0;
            prediction_parameters.angle_delta[0] = 0;
            prediction_parameters.angle_delta[1] = 0;
            prediction_parameters.use_filter_intra = false;
            prediction_parameters.is_wedge_inter_intra = this.reader_.readSymbol(this.symbol_decoder_context_.is_wedge_inter_intra_cdf[block.size]);
            if (prediction_parameters.is_wedge_inter_intra) {
               prediction_parameters.wedge_index = this.reader_.readSymbol(this.symbol_decoder_context_.wedge_index_cdf[block.size], 16);
               prediction_parameters.wedge_sign = 0;
            }
         }
      }
   }

   private void readMotionMode(Block block, boolean isCompound, boolean skipMode) {
      LogWriter.writeLog("ReadMotionMode not to be called");
      D.BlockParams bp = block.bp;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      int global_motion_type = this.frame_header_.global_motion[bp.reference_frame[0]].type;
      if (!skipMode
         && this.frame_header_.is_motion_mode_switchable
         && !D.IsBlockDimension4(block.size)
         && (this.frame_header_.force_integer_mv != 0 || bp.y_mode != 16 && bp.y_mode != 24 || global_motion_type <= 1)
         && !isCompound
         && bp.reference_frame[1] != 0
         && block.HasOverlappableCandidates()) {
         prediction_parameters.num_warp_samples = 0;
         int num_samples_scanned = 0;

         for (int i = 0; i < prediction_parameters.warp_estimate_candidates.length; i++) {
            for (int j = 0; j < prediction_parameters.warp_estimate_candidates[0].length; j++) {
               prediction_parameters.warp_estimate_candidates[i][j] = 0;
            }
         }

         this.findWarpSamples(block, prediction_parameters.num_warp_samples, num_samples_scanned, prediction_parameters.warp_estimate_candidates);
         if (this.frame_header_.force_integer_mv == 0
            && prediction_parameters.num_warp_samples != 0
            && this.frame_header_.allow_warped_motion
            && !this.IsScaled(bp.reference_frame[0])) {
            prediction_parameters.motion_mode = this.reader_.readSymbol(this.symbol_decoder_context_.motion_mode_cdf[block.size], 3);
         } else {
            prediction_parameters.motion_mode = this.reader_.readSymbol(this.symbol_decoder_context_.use_obmc_cdf[block.size]) ? 1 : 0;
         }
      } else {
         prediction_parameters.motion_mode = 0;
      }
   }

   private boolean IsScaled(int type) {
      int index = this.frame_header_.reference_frame_index[type - 1];
      return this.reference_frames_[index].upscaled_width() != this.frame_header_.width
         || this.reference_frames_[index].frame_height() != this.frame_header_.height;
   }

   private void findWarpSamples(Block block, int numWarpSamples, int numSamplesScanned, int[][] warpEstimateCandidates) {
      LogWriter.writeLog("Find Warp Samples not implemented");
   }

   private int[] getIsExplicitCompoundTypeCdf(Block block) {
      int context = 0;
      if (block.topAvail[0]) {
         if (!block.IsTopSingle()) {
            context += block.top_context.is_explicit_compound_type[block.top_context_index] ? 1 : 0;
         } else if (block.TopReference(0) == 7) {
            context += 3;
         }
      }

      if (block.leftAvail[0]) {
         if (!block.IsLeftSingle()) {
            context += this.left_context_.is_explicit_compound_type[block.left_context_index] ? 1 : 0;
         } else if (block.LeftReference(0) == 7) {
            context += 3;
         }
      }

      return this.symbol_decoder_context_.is_explicit_compound_type_cdf[Math.min(context, 5)];
   }

   private final int[] getIsCompoundTypeAverageCdf(Block block) {
      D.BlockParams bp = block.bp;
      D.ReferenceInfo reference_info = this.current_frame_.reference_info();
      int forward = Math.abs(reference_info.relative_distance_from[bp.reference_frame[0]]);
      int backward = Math.abs(reference_info.relative_distance_from[bp.reference_frame[1]]);
      int context = forward == backward ? 3 : 0;
      if (block.topAvail[0]) {
         if (!block.IsTopSingle()) {
            context += block.top_context.is_compound_type_average[block.top_context_index] ? 1 : 0;
         } else if (block.TopReference(0) == 7) {
            context++;
         }
      }

      if (block.leftAvail[0]) {
         if (!block.IsLeftSingle()) {
            context += this.left_context_.is_compound_type_average[block.left_context_index] ? 1 : 0;
         } else if (block.LeftReference(0) == 7) {
            context++;
         }
      }

      return this.symbol_decoder_context_.is_compound_type_average_cdf[context];
   }

   private void readCompoundType(Block block, boolean isCompound, boolean skipMode, boolean[] isExplicitCompoundType, boolean[] isCompoundTypeAverage) {
      isExplicitCompoundType[0] = false;
      isCompoundTypeAverage[0] = true;
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      if (skipMode) {
         prediction_parameters.compound_prediction_type = 2;
      } else if (isCompound) {
         if (this.sequence_header_.enable_masked_compound) {
            isExplicitCompoundType[0] = this.reader_.readSymbol(this.getIsExplicitCompoundTypeCdf(block));
         }

         if (isExplicitCompoundType[0]) {
            if (D.kIsWedgeCompoundModeAllowed.Contains(block.size)) {
               prediction_parameters.compound_prediction_type = this.reader_.readSymbol(this.symbol_decoder_context_.compound_type_cdf[block.size]) ? 1 : 0;
            } else {
               prediction_parameters.compound_prediction_type = 1;
            }
         } else {
            if (!this.sequence_header_.enable_jnt_comp) {
               prediction_parameters.compound_prediction_type = 2;
               return;
            }

            isCompoundTypeAverage[0] = this.reader_.readSymbol(this.getIsCompoundTypeAverageCdf(block));
            prediction_parameters.compound_prediction_type = isCompoundTypeAverage[0] ? 2 : 4;
         }

         if (prediction_parameters.compound_prediction_type == 0) {
            prediction_parameters.wedge_index = this.reader_.readSymbol(this.symbol_decoder_context_.wedge_index_cdf[block.size], 16);
            prediction_parameters.wedge_sign = this.reader_.readBit();
         } else if (prediction_parameters.compound_prediction_type == 1) {
            prediction_parameters.mask_is_inverse = this.reader_.readBit() != 0;
         }
      } else if (prediction_parameters.inter_intra_mode != 4) {
         prediction_parameters.compound_prediction_type = prediction_parameters.is_wedge_inter_intra ? 0 : 3;
      } else {
         prediction_parameters.compound_prediction_type = 2;
      }
   }

   private int[] getInterpolationFilterCdf(Block block, int direction) {
      D.BlockParams bp = block.bp;
      int context = direction * 8 + (bp.reference_frame[1] > 0 ? 1 : 0) * 4;
      int top_type = 3;
      if (block.topAvail[0] && (block.bp_top.reference_frame[0] == bp.reference_frame[0] || block.bp_top.reference_frame[1] == bp.reference_frame[0])) {
         top_type = block.bp_top.interpolation_filter[direction];
      }

      int left_type = 3;
      if (block.leftAvail[0] && (block.bp_left.reference_frame[0] == bp.reference_frame[0] || block.bp_left.reference_frame[1] == bp.reference_frame[0])) {
         left_type = block.bp_left.interpolation_filter[direction];
      }

      if (left_type == top_type) {
         context += left_type;
      } else if (left_type == 3) {
         context += top_type;
      } else if (top_type == 3) {
         context += left_type;
      } else {
         context += 3;
      }

      return this.symbol_decoder_context_.interpolation_filter_cdf[context];
   }

   private void readInterpolationFilter(Block block, boolean skip_mode) {
      D.BlockParams bp = block.bp;
      if (this.frame_header_.interpolation_filter != 4) {
         for (int i = 0; i < bp.interpolation_filter.length; i++) {
            bp.interpolation_filter[i] = this.frame_header_.interpolation_filter;
         }
      } else {
         boolean interpolation_filter_present = true;
         if (skip_mode || block.bp.prediction_parameters.motion_mode == 2) {
            interpolation_filter_present = false;
         } else if (!D.IsBlockDimension4(block.size) && bp.y_mode == 16) {
            interpolation_filter_present = this.frame_header_.global_motion[bp.reference_frame[0]].type == 1;
         } else if (!D.IsBlockDimension4(block.size) && bp.y_mode == 24) {
            interpolation_filter_present = this.frame_header_.global_motion[bp.reference_frame[0]].type == 1
               || this.frame_header_.global_motion[bp.reference_frame[1]].type == 1;
         }

         for (int i = 0; i < (this.sequence_header_.enable_dual_filter ? 2 : 1); i++) {
            bp.interpolation_filter[i] = interpolation_filter_present ? this.reader_.readSymbol(this.getInterpolationFilterCdf(block, i), 3) : 0;
         }

         if (!this.sequence_header_.enable_dual_filter) {
            bp.interpolation_filter[1] = bp.interpolation_filter[0];
         }
      }
   }

   private void setCdfContextCompoundType(Block block, boolean isExplicitCompoundType, boolean isCompoundTypeAverage) {
      Mem.set(this.left_context_.is_explicit_compound_type, block.left_context_index, isExplicitCompoundType, block.height4x4);
      Mem.set(this.left_context_.is_compound_type_average, block.left_context_index, isCompoundTypeAverage, block.height4x4);
      Mem.set(block.top_context.is_explicit_compound_type, block.top_context_index, isExplicitCompoundType, block.width4x4);
      Mem.set(block.top_context.is_compound_type_average, block.top_context_index, isCompoundTypeAverage, block.width4x4);
   }

   private boolean readInterBlockModeInfo(Block block, boolean skipMode) {
      D.BlockParams bp = block.bp;
      bp.prediction_parameters.palette_mode_info.size[0] = 0;
      bp.prediction_parameters.palette_mode_info.size[1] = 0;
      this.setCdfContextPaletteSize(block);
      this.readReferenceFrames(block, skipMode);
      boolean is_compound = bp.reference_frame[1] > 0;
      D.MvContexts mode_contexts = new D.MvContexts();
      this.findMvStack(block, is_compound, mode_contexts);
      this.readInterPredictionModeY(block, mode_contexts, skipMode);
      this.readRefMvIndex(block);
      if (!this.assignInterMv(block, is_compound)) {
         return false;
      } else {
         this.readInterIntraMode(block, is_compound, skipMode);
         this.readMotionMode(block, is_compound, skipMode);
         boolean[] is_explicit_compound_type = new boolean[]{false};
         boolean[] is_compound_type_average = new boolean[]{false};
         this.readCompoundType(block, is_compound, skipMode, is_explicit_compound_type, is_compound_type_average);
         this.setCdfContextCompoundType(block, is_explicit_compound_type[0], is_compound_type_average[0]);
         this.readInterpolationFilter(block, skipMode);
         return true;
      }
   }

   private void setCdfContextSkipMode(Block block, boolean skip_mode) {
      Mem.set(this.left_context_.skip_mode, block.left_context_index, skip_mode, block.height4x4);
      Mem.set(block.top_context.skip_mode, block.top_context_index, skip_mode, block.width4x4);
   }

   private boolean decodeInterModeInfo(Block block) {
      D.BlockParams bp = block.bp;
      block.bp.prediction_parameters.use_intra_block_copy = false;
      bp.skip = false;
      if (!this.readInterSegmentId(block, true)) {
         return false;
      } else {
         boolean skip_mode = this.readSkipMode(block);
         this.setCdfContextSkipMode(block, skip_mode);
         if (skip_mode) {
            bp.skip = true;
         } else {
            this.readSkip(block);
         }

         if (!this.frame_header_.segmentation.segment_id_pre_skip && !this.readInterSegmentId(block, false)) {
            return false;
         } else {
            this.readCdef(block);
            if (this.read_deltas_) {
               this.readQuantizerIndexDelta(block);
               this.readLoopFilterDelta(block);
               this.read_deltas_ = false;
            }

            this.readIsInter(block, skip_mode);
            return bp.is_inter ? this.readInterBlockModeInfo(block, skip_mode) : this.readIntraBlockModeInfo(block, false);
         }
      }
   }

   private boolean decodeModeInfo(Block block) {
      return D.IsIntraFrame(this.frame_header_.frame_type) ? this.decodeIntraModeInfo(block) : this.decodeInterModeInfo(block);
   }

   private static void merge(int[] f1, int f1Len, int[] f2, int f2Len, int[] dest) {
      int f1Pos = 0;
      int f2Pos = 0;

      int dPos;
      for (dPos = 0; f1Pos != f1Len; dPos++) {
         if (f2Pos == f2Len) {
            int len = f1Len - f1Pos;
            System.arraycopy(f1, f1Pos, dest, dPos, len);
            return;
         }

         if (f2[f2Pos] < f1[f1Pos]) {
            dest[dPos] = f2[f2Pos];
            f2Pos++;
         } else {
            dest[dPos] = f1[f1Pos];
            f1Pos++;
         }
      }

      int len = f2Len - f2Pos;
      System.arraycopy(f2, f2Pos, dest, dPos, len);
   }

   private int getPaletteCache(Block block, int planeType, int[] cache) {
      int top_size = block.topAvail[0] && block.row4x4 * 4 % 64 != 0 ? block.top_context.palette_size[planeType][block.top_context_index] : 0;
      int left_size = block.leftAvail[0] ? this.left_context_.palette_size[planeType][block.left_context_index] : 0;
      if (left_size == 0 && top_size == 0) {
         return 0;
      } else {
         int[] empty_palette = new int[]{0};
         int[] top = top_size > 0 ? block.top_context.palette_color[block.top_context_index][planeType] : empty_palette;
         int[] left = left_size > 0 ? this.left_context_.palette_color[block.left_context_index][planeType] : empty_palette;
         int[] tempCache = new int[top_size + left_size];
         merge(top, top_size, left, left_size, tempCache);
         int[] dists = Arrays.stream(tempCache).distinct().toArray();

         for (int i = 0; i < dists.length; i++) {
            cache[i] = dists[i];
         }

         return dists.length;
      }
   }

   private void readPaletteColors(Block block, int plane) {
      int plane_type = D.GetPlaneType(plane);
      int[] cache = new int[16];
      int n = this.getPaletteCache(block, plane_type, cache);
      D.BlockParams bp = block.bp;
      int palette_size = bp.prediction_parameters.palette_mode_info.size[plane_type];
      int[] palette_color = bp.prediction_parameters.palette_mode_info.color[plane];
      int bitdepth = this.sequence_header_.color_config.bitdepth;
      int index = 0;

      for (int i = 0; i < n && index < palette_size; i++) {
         if (this.reader_.readBit() != 0) {
            palette_color[index++] = cache[i];
         }
      }

      if (index < palette_size) {
         palette_color[index++] = (int)this.reader_.readLiteral(bitdepth);
      }

      int max_value = (1 << bitdepth) - 1;
      if (index < palette_size) {
         int bits = bitdepth - 3 + (int)this.reader_.readLiteral(2);

         do {
            int delta = (int)(this.reader_.readLiteral(bits) + (plane_type == 0 ? 1 : 0));
            palette_color[index] = Math.min(palette_color[index - 1] + delta, max_value);
            if (palette_color[index] + (plane_type == 0 ? 1 : 0) >= max_value) {
               Mem.set(palette_color, index + 1, max_value, palette_size - index - 1);
               break;
            }

            int range = (1 << bitdepth) - palette_color[index] - (plane_type == 0 ? 1 : 0);
            bits = Math.min(bits, D.CeilLog2(range));
         } while (++index < palette_size);
      }

      int[] temp = new int[palette_size];

      for (int ix = 0; ix < palette_size; ix++) {
         temp[ix] = palette_color[ix];
      }

      Arrays.sort(temp);

      for (int ix = 0; ix < palette_size; ix++) {
         palette_color[ix] = temp[ix];
      }

      if (plane_type == 1) {
         int[] palette_color_v = bp.prediction_parameters.palette_mode_info.color[2];
         if (this.reader_.readBit() != 0) {
            int bits = bitdepth - 4 + (int)this.reader_.readLiteral(2);
            palette_color_v[0] = (int)this.reader_.readLiteral(bitdepth);

            for (int ix = 1; ix < palette_size; ix++) {
               int delta = (int)this.reader_.readLiteral(bits);
               if (delta != 0 && this.reader_.readBit() != 0) {
                  delta = -delta;
               }

               palette_color_v[ix] = palette_color_v[ix - 1] + delta & max_value;
            }
         } else {
            for (int ix = 0; ix < palette_size; ix++) {
               palette_color_v[ix] = (int)this.reader_.readLiteral(bitdepth);
            }
         }
      }
   }

   private void readPaletteModeInfo(Block block) {
      D.BlockParams bp = block.bp;
      bp.prediction_parameters.palette_mode_info.size[0] = 0;
      bp.prediction_parameters.palette_mode_info.size[1] = 0;
      if (!D.IsBlockSmallerThan8x8(block.size) && block.size <= 18 && this.frame_header_.allow_screen_content_tools) {
         int block_size_context = D.k4x4WidthLog2[block.size] + D.k4x4HeightLog2[block.size] - 2;
         if (bp.y_mode == 0) {
            int context = (block.topAvail[0] && block.top_context.palette_size[0][block.top_context_index] > 0 ? 1 : 0)
               + (block.leftAvail[0] && this.left_context_.palette_size[0][block.left_context_index] > 0 ? 1 : 0);
            boolean has_palette_y = this.reader_.readSymbol(this.symbol_decoder_context_.has_palette_y_cdf[block_size_context][context]);
            if (has_palette_y) {
               bp.prediction_parameters.palette_mode_info.size[0] = 2
                  + this.reader_.readSymbol(this.symbol_decoder_context_.palette_y_size_cdf[block_size_context], 7);
               this.readPaletteColors(block, 0);
            }
         }

         if (block.HasChroma() && bp.prediction_parameters.uv_mode == 0) {
            int context = bp.prediction_parameters.palette_mode_info.size[0] > 0 ? 1 : 0;
            boolean has_palette_uv = this.reader_.readSymbol(this.symbol_decoder_context_.has_palette_uv_cdf[context]);
            if (has_palette_uv) {
               bp.prediction_parameters.palette_mode_info.size[1] = 2
                  + this.reader_.readSymbol(this.symbol_decoder_context_.palette_uv_size_cdf[block_size_context], 7);
               this.readPaletteColors(block, 1);
            }
         }
      }
   }

   private void populatePaletteColorContexts(Block block, int plane_type, int i, int start, int end, int[][] color_order, int[] color_context) {
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      int column = start;

      for (int counter = 0; column >= end; counter++) {
         int row = i - column;
         int top = row > 0 ? prediction_parameters.color_index_map[plane_type][row - 1][column] : 0;
         int left = column > 0 ? prediction_parameters.color_index_map[plane_type][row][column - 1] : 0;
         int index_mask;
         int index;
         if (column <= 0) {
            color_context[counter] = 0;
            color_order[counter][0] = top;
            index_mask = 1 << top;
            index = 1;
         } else if (row <= 0) {
            color_context[counter] = 0;
            color_order[counter][0] = left;
            index_mask = 1 << left;
            index = 1;
         } else {
            int top_left = prediction_parameters.color_index_map[plane_type][row - 1][column - 1];
            index_mask = 1 << top | 1 << left | 1 << top_left;
            if (top == left && top == top_left) {
               color_context[counter] = 4;
               color_order[counter][0] = top;
               index = 1;
            } else if (top == left) {
               color_context[counter] = 3;
               color_order[counter][0] = top;
               color_order[counter][1] = top_left;
               index = 2;
            } else if (top == top_left) {
               color_context[counter] = 2;
               color_order[counter][0] = top_left;
               color_order[counter][1] = left;
               index = 2;
            } else if (left == top_left) {
               color_context[counter] = 2;
               color_order[counter][0] = top_left;
               color_order[counter][1] = top;
               index = 2;
            } else {
               color_context[counter] = 1;
               color_order[counter][0] = Math.min(top, left);
               color_order[counter][1] = Math.max(top, left);
               color_order[counter][2] = top_left;
               index = 3;
            }
         }

         for (int j = 0; j < 8; j++) {
            if (!D.BitMaskSet.MaskContainsValue(index_mask, j)) {
               color_order[counter][index++] = j;
            }
         }

         column--;
      }
   }

   private boolean readPaletteTokens(Block block) {
      D.PaletteModeInfo palette_mode_info = block.bp.prediction_parameters.palette_mode_info;
      D.PredictionParams predictionParameters = block.bp.prediction_parameters;

      for (int plane_type = 0; plane_type < (block.HasChroma() ? 2 : 1); plane_type++) {
         int palette_size = palette_mode_info.size[plane_type];
         if (palette_size != 0) {
            int block_height = block.height;
            int block_width = block.width;
            int screen_height = Math.min(block_height, (this.frame_header_.rows4x4 - block.row4x4) * 4);
            int screen_width = Math.min(block_width, (this.frame_header_.columns4x4 - block.column4x4) * 4);
            if (plane_type == 1) {
               block_height >>= this.sequence_header_.color_config.subsampling_y;
               block_width >>= this.sequence_header_.color_config.subsampling_x;
               screen_height >>= this.sequence_header_.color_config.subsampling_y;
               screen_width >>= this.sequence_header_.color_config.subsampling_x;
               if (block_height < 4) {
                  block_height += 2;
                  screen_height += 2;
               }

               if (block_width < 4) {
                  block_width += 2;
                  screen_width += 2;
               }
            }

            if (predictionParameters.color_index_map[plane_type] == null) {
               predictionParameters.color_index_map[plane_type] = new int[64][64];
            }

            for (int h = 0; h < block_height; h++) {
               for (int w = 0; w < block_width; w++) {
                  predictionParameters.color_index_map[plane_type][h][w] = 0;
               }
            }

            int first_value = this.reader_.decodeUniform(palette_size);
            predictionParameters.color_index_map[plane_type][0][0] = first_value;

            for (int i = 1; i < screen_height + screen_width - 1; i++) {
               int start = Math.min(i, screen_width - 1);
               int end = Math.max(0, i - screen_height + 1);
               int[][] color_order = new int[64][8];
               int[] color_context = new int[64];
               this.populatePaletteColorContexts(block, plane_type, i, start, end, color_order, color_context);
               int j = start;

               for (int counter = 0; j >= end; counter++) {
                  int[] cdf = this.symbol_decoder_context_.palette_color_index_cdf[plane_type][palette_size - 2][color_context[counter]];
                  int color_order_index = this.reader_.readSymbol(cdf, palette_size);
                  predictionParameters.color_index_map[plane_type][i - j][j] = color_order[counter][color_order_index];
                  j--;
               }
            }

            if (screen_width < block_width) {
               for (int i = 0; i < screen_height; i++) {
                  Mem.set(
                     predictionParameters.color_index_map[plane_type][i],
                     screen_width,
                     predictionParameters.color_index_map[plane_type][i][screen_width - 1],
                     block_width - screen_width
                  );
               }
            }

            for (int i = screen_height; i < block_height; i++) {
               Mem.cpy(predictionParameters.color_index_map[plane_type][i], predictionParameters.color_index_map[plane_type][screen_height - 1], block_width);
            }
         }
      }

      return true;
   }

   private static int partitionCdfGatherHorizontalAlike(int[] partition_cdf, int blockSize) {
      int cdf = partition_cdf[0] - partition_cdf[1] + partition_cdf[2] - partition_cdf[6];
      if (blockSize != 21) {
         cdf += partition_cdf[7] - partition_cdf[8];
      }

      return cdf & 65535;
   }

   private static int partitionCdfGatherVerticalAlike(int[] partitionCdf, int blockSize) {
      int cdf = partitionCdf[1] + partitionCdf[5] - partitionCdf[4];
      if (blockSize != 21) {
         cdf += partitionCdf[8] - partitionCdf[7];
      }

      return cdf & 65535;
   }

   private int[] getPartitionCdf(int row4x4, int column4x4, int blockSize) {
      int block_size_log2 = D.k4x4WidthLog2[blockSize];
      int top = 0;
      if (this.IsTopInside(row4x4)) {
         top = D.k4x4WidthLog2[this.block_parameters_holder_.Find(row4x4 - 1, column4x4).size] < block_size_log2 ? 1 : 0;
      }

      int left = 0;
      if (this.IsLeftInside(column4x4)) {
         left = D.k4x4HeightLog2[this.block_parameters_holder_.Find(row4x4, column4x4 - 1).size] < block_size_log2 ? 1 : 0;
      }

      int context = left * 2 + top;
      return this.symbol_decoder_context_.partition_cdf[block_size_log2 - 1][context];
   }

   private boolean readPartition(int row4x4, int column4x4, int block_size, boolean hasRows, boolean hasColumns, int[] partition) {
      if (D.IsBlockSmallerThan8x8(block_size)) {
         partition[0] = 0;
         return true;
      } else if (!hasRows && !hasColumns) {
         partition[0] = 3;
         return true;
      } else {
         int[] partition_cdf = this.getPartitionCdf(row4x4, column4x4, block_size);
         if (partition_cdf == null) {
            return false;
         } else {
            if (hasRows && hasColumns) {
               int bsize_log2 = D.k4x4WidthLog2[block_size];
               if (bsize_log2 == 1) {
                  partition[0] = this.reader_.readSymbol(partition_cdf, 4);
               } else if (bsize_log2 == 5) {
                  partition[0] = this.reader_.readSymbol(partition_cdf, 8);
               } else {
                  partition[0] = this.reader_.readSymbol(partition_cdf, 10);
               }
            } else if (hasColumns) {
               int cdf = partitionCdfGatherVerticalAlike(partition_cdf, block_size);
               partition[0] = this.reader_.readSymbolWithoutCdfUpdate(cdf) ? 3 : 1;
            } else {
               int cdf = partitionCdfGatherHorizontalAlike(partition_cdf, block_size);
               partition[0] = this.reader_.readSymbolWithoutCdfUpdate(cdf) ? 3 : 2;
            }

            return true;
         }
      }
   }

   private static int getSquareTransformSize(int pixels) {
      switch (pixels) {
         case 8:
            return 4;
         case 16:
            return 9;
         case 32:
            return 14;
         case 64:
         case 128:
            return 18;
         default:
            return 0;
      }
   }

   private int getTopTransformWidth(Block block, int row4x4, int column4x4, boolean ignoreSkip) {
      if (row4x4 == block.row4x4) {
         if (!block.topAvail[0]) {
            return 64;
         }

         D.BlockParams bp_top = this.block_parameters_holder_.Find(row4x4 - 1, column4x4);
         if ((ignoreSkip || bp_top.skip) && bp_top.is_inter) {
            return D.kBlockWidthPixels[bp_top.size];
         }
      }

      return D.kTransformWidth[this.inter_transform_sizes_[row4x4 - 1][column4x4]];
   }

   private int getLeftTransformHeight(Block block, int row4x4, int column4x4, boolean ignoreSkip) {
      if (column4x4 == block.column4x4) {
         if (!block.leftAvail[0]) {
            return 64;
         }

         D.BlockParams bp_left = this.block_parameters_holder_.Find(row4x4, column4x4 - 1);
         if ((ignoreSkip || bp_left.skip) && bp_left.is_inter) {
            return D.kBlockHeightPixels[bp_left.size];
         }
      }

      return D.kTransformHeight[this.inter_transform_sizes_[row4x4][column4x4 - 1]];
   }

   private int readFixedTransformSize(Block block) {
      D.BlockParams bp = block.bp;
      if (this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id]) {
         return 0;
      } else {
         int max_rect_tx_size = D.kMaxTransformSizeRectangle[block.size];
         boolean allow_select = !bp.skip || !bp.is_inter;
         if (block.size != 0 && allow_select && this.frame_header_.tx_mode == 2) {
            int max_tx_width = D.kTransformWidth[max_rect_tx_size];
            int max_tx_height = D.kTransformHeight[max_rect_tx_size];
            int top_width = block.topAvail[0] ? this.getTopTransformWidth(block, block.row4x4, block.column4x4, true) : 0;
            int left_height = block.leftAvail[0] ? this.getLeftTransformHeight(block, block.row4x4, block.column4x4, true) : 0;
            int context = (top_width >= max_tx_width ? 1 : 0) + (left_height >= max_tx_height ? 1 : 0);
            int cdf_index = D.kTxDepthCdfIndex[block.size];
            int[] cdf = this.symbol_decoder_context_.tx_depth_cdf[cdf_index][context];
            int tx_depth = cdf_index == 0 ? (this.reader_.readSymbol(cdf) ? 1 : 0) : this.reader_.readSymbol(cdf, 3);
            if (tx_depth == 0) {
               return max_rect_tx_size;
            } else {
               int tx_size = D.kSplitTransformSize[max_rect_tx_size];
               return tx_depth == 1 ? tx_size : D.kSplitTransformSize[tx_size];
            }
         } else {
            return max_rect_tx_size;
         }
      }
   }

   private void readVariableTransformTree(Block block, int row4x4, int column4x4, int txSize) {
      int pixels = Math.max(block.width, block.height);
      int max_tx_size = getSquareTransformSize(pixels);
      int context_delta = (4 - D.TransformSizeToSquareTransformIndex(max_tx_size)) * 6;
      Stack<D.TransformTreeNode> stack = new Stack<>();
      stack.push(new D.TransformTreeNode(column4x4, row4x4, txSize, 0));

      do {
         D.TransformTreeNode node = stack.pop();
         int tx_width4x4 = D.kTransformWidth4x4[node.tx_size];
         int tx_height4x4 = D.kTransformHeight4x4[node.tx_size];
         if (node.tx_size != 0 && node.depth != 2) {
            int top = this.getTopTransformWidth(block, node.y, node.x, false) < D.kTransformWidth[node.tx_size] ? 1 : 0;
            int left = this.getLeftTransformHeight(block, node.y, node.x, false) < D.kTransformHeight[node.tx_size] ? 1 : 0;
            int context = (max_tx_size > 4 && D.kTransformSizeSquareMax[node.tx_size] != max_tx_size ? 1 : 0) * 3 + context_delta + top + left;
            if (this.reader_.readSymbol(this.symbol_decoder_context_.tx_split_cdf[context])) {
               int sub_tx_size = D.kSplitTransformSize[node.tx_size];
               int step_width4x4 = D.kTransformWidth4x4[sub_tx_size];
               int step_height4x4 = D.kTransformHeight4x4[sub_tx_size];

               for (int i = tx_height4x4 - step_height4x4; i >= 0; i -= step_height4x4) {
                  for (int j = tx_width4x4 - step_width4x4; j >= 0; j -= step_width4x4) {
                     if (node.y + i < this.frame_header_.rows4x4 && node.x + j < this.frame_header_.columns4x4) {
                        stack.push(new D.TransformTreeNode(node.x + j, node.y + i, sub_tx_size, node.depth + 1));
                     }
                  }
               }
               continue;
            }
         }

         for (int i = 0; i < tx_height4x4; i++) {
            Mem.set(this.inter_transform_sizes_[node.y + i], node.x, node.tx_size, tx_width4x4);
         }
      } while (!stack.isEmpty());
   }

   private void decodeTransformSize(Block block) {
      D.BlockParams bp = block.bp;
      if (this.frame_header_.tx_mode == 2
         && block.size > 0
         && bp.is_inter
         && !bp.skip
         && !this.frame_header_.segmentation.lossless[bp.prediction_parameters.segment_id]) {
         int max_tx_size = D.kMaxTransformSizeRectangle[block.size];
         int tx_width4x4 = D.kTransformWidth4x4[max_tx_size];
         int tx_height4x4 = D.kTransformHeight4x4[max_tx_size];

         for (int row = block.row4x4; row < block.row4x4 + block.height4x4; row += tx_height4x4) {
            for (int column = block.column4x4; column < block.column4x4 + block.width4x4; column += tx_width4x4) {
               this.readVariableTransformTree(block, row, column, max_tx_size);
            }
         }
      } else {
         int transform_size = this.readFixedTransformSize(block);

         for (int row = block.row4x4; row < block.row4x4 + block.height4x4; row++) {
            Mem.set(this.inter_transform_sizes_[row], block.column4x4, transform_size, block.width4x4);
         }
      }
   }

   private static int getDirectionalIntraPredictorDerivative(int angle) {
      if (angle < 3 || angle > 87) {
         LogWriter.writeLog("Tile: GetDirectionalIntraPredictorDerivative error ");
      }

      return D.kDirectionalIntraPredictorDerivative[angle / 2 - 1];
   }

   private static int getWedgeBlockSizeIndex(int block_size) {
      if (block_size < 4) {
         LogWriter.writeLog("Tile: GetWedgeBlockSizeIndex error ");
      }

      return block_size - 4 - (block_size >= 8 ? 1 : 0) - (block_size >= 12 ? 1 : 0);
   }

   private static int getInterIntraMaskLookupIndex(int dimension) {
      return D.FloorLog2(dimension) - 2;
   }

   private static int getIntraEdgeFilterStrength(int width, int height, int filterType, int delta) {
      int sum = width + height;
      delta = Math.abs(delta);
      if (filterType == 0) {
         if (sum <= 8) {
            if (delta >= 56) {
               return 1;
            }
         } else if (sum <= 16) {
            if (delta >= 40) {
               return 1;
            }
         } else {
            if (sum > 24) {
               if (sum <= 32) {
                  if (delta >= 32) {
                     return 3;
                  }

                  if (delta >= 4) {
                     return 2;
                  }

                  return 1;
               }

               return 3;
            }

            if (delta >= 32) {
               return 3;
            }

            if (delta >= 16) {
               return 2;
            }

            if (delta >= 8) {
               return 1;
            }
         }
      } else if (sum <= 8) {
         if (delta >= 64) {
            return 2;
         }

         if (delta >= 40) {
            return 1;
         }
      } else if (sum <= 16) {
         if (delta >= 48) {
            return 2;
         }

         if (delta >= 20) {
            return 1;
         }
      } else {
         if (sum > 24) {
            return 3;
         }

         if (delta >= 4) {
            return 3;
         }
      }

      return 0;
   }

   private static boolean doIntraEdgeUpsampling(int width, int height, int filterType, int delta) {
      int sum = width + height;
      delta = Math.abs(delta);
      if (delta >= 40) {
         return false;
      } else {
         return filterType == 1 ? sum <= 8 : sum <= 16;
      }
   }

   private void getDistanceWeights(int[] distance, int[] weight) {
      int order = distance[0] <= distance[1] ? 1 : 0;
      if (distance[0] != 0 && distance[1] != 0) {
         int i;
         for (i = 0; i < 3; i++) {
            int w0 = D.kQuantizedDistanceWeight[i][order];
            int w1 = D.kQuantizedDistanceWeight[i][1 - order];
            if (order == 0 ? distance[0] * w0 < distance[1] * w1 : distance[0] * w0 > distance[1] * w1) {
               break;
            }
         }

         weight[0] = D.kQuantizedDistanceLookup[i][order];
         weight[1] = D.kQuantizedDistanceLookup[i][1 - order];
      } else {
         weight[0] = D.kQuantizedDistanceLookup[3][order];
         weight[1] = D.kQuantizedDistanceLookup[3][1 - order];
      }
   }

   private static int getIntraPredictor(int mode, boolean hasLeft, boolean hasTop) {
      if (mode == 0) {
         if (hasLeft && hasTop) {
            return 3;
         } else if (hasLeft) {
            return 2;
         } else {
            return hasTop ? 1 : 0;
         }
      } else {
         switch (mode) {
            case 9:
               return 7;
            case 10:
               return 8;
            case 11:
               return 9;
            case 12:
               return 6;
            default:
               return 10;
         }
      }
   }

   private static int getStartPoint(D.Array2DView[] buffer, int plane, int x, int y, int bitdepth) {
      return buffer[plane].get(y, x);
   }

   private static int getPixelPositionFromHighScale(int start, int step, int offset) {
      return start + step * offset >> 10;
   }

   private void intraPrediction(
      Block block, int plane, int x, int y, boolean has_left, boolean has_top, boolean has_top_right, boolean has_bottom_left, int mode, int tx_size
   ) {
      int width = D.kTransformWidth[tx_size];
      int height = D.kTransformHeight[tx_size];
      int x_shift = this.subsampling_x_[plane];
      int y_shift = this.subsampling_y_[plane];
      int max_x = (this.frame_header_.columns4x4 * 4 >> x_shift) - 1;
      int max_y = (this.frame_header_.rows4x4 * 4 >> y_shift) - 1;
      int[] top_row = new int[160];
      int[] left_column = new int[160];
      int top_rowPos = 16;
      int left_columnPos = 16;
      int bitdepth = this.sequence_header_.color_config.bitdepth;
      int top_and_left_size = width + height;
      boolean is_directional_mode = D.IsDirectionalMode(mode);
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      boolean use_filter_intra = plane == 0 && prediction_parameters.use_filter_intra;
      int prediction_angle = is_directional_mode ? D.kPredictionModeToAngle[mode] + prediction_parameters.angle_delta[D.GetPlaneType(plane)] * 3 : 0;
      int top_size = is_directional_mode ? top_and_left_size : width;
      int left_size = is_directional_mode ? top_and_left_size : height;
      int top_right_size = is_directional_mode ? (has_top_right ? 2 : 1) * width : width;
      int bottom_left_size = is_directional_mode ? (has_bottom_left ? 2 : 1) * height : height;
      D.Array2DView buffer = this.buffer_[plane];
      boolean needs_top = use_filter_intra || D.kNeedsLeftAndTop.Contains(mode) || is_directional_mode && prediction_angle < 180 || mode == 0 && has_top;
      boolean needs_left = use_filter_intra || D.kNeedsLeftAndTop.Contains(mode) || is_directional_mode && prediction_angle > 90 || mode == 0 && has_left;
      int[] bufferData = buffer.data_;
      int bufferStride = buffer.columns();
      int[] top_row_src = buffer.data_;
      int top_row_srcPos = (y - 1) * bufferStride;
      if ((needs_top || needs_left) && this.use_intra_prediction_buffer_) {
         int current_superblock_index = block.row4x4 >> (this.sequence_header_.use_128x128_superblock ? 5 : 4);
         int plane_shift = (this.sequence_header_.use_128x128_superblock ? 7 : 6) - this.subsampling_y_[plane];
         int top_row_superblock_index = y - 1 >> plane_shift;
         if (current_superblock_index != top_row_superblock_index) {
            top_row_src = this.intra_prediction_buffer_[plane].get();
            top_row_srcPos = 0;
         }
      }

      if (needs_top) {
         if (!has_top && !has_left) {
            top_row[15] = 1 << bitdepth - 1;
         } else {
            int left_index = has_left ? x - 1 : x;
            top_row[15] = has_top ? top_row_src[top_row_srcPos + left_index] : bufferData[y * bufferStride + left_index];
         }

         if (!has_top && has_left) {
            Mem.set(top_row, 16, bufferData[y * bufferStride + x - 1], top_size);
         } else if (!has_top && !has_left) {
            Mem.set(top_row, 16 + (1 << bitdepth - 1) - 1, top_size);
         } else {
            int top_limit = Math.min(max_x - x + 1, top_right_size);
            Mem.cpy(top_row, 16, top_row_src, top_row_srcPos + x, top_limit);
            if (top_size - top_limit > 0) {
               Mem.set(top_row, 16 + top_limit, top_row_src[top_row_srcPos + top_limit + x - 1], top_size - top_limit);
            }
         }
      }

      if (needs_left) {
         if (!has_top && !has_left) {
            left_column[15] = 1 << bitdepth - 1;
         } else {
            int left_index = has_left ? x - 1 : x;
            left_column[15] = has_top ? top_row_src[top_row_srcPos + left_index] : bufferData[y * bufferStride + left_index];
         }

         if (!has_left && has_top) {
            Mem.set(left_column, 16, top_row_src[top_row_srcPos + x], left_size);
         } else if (!has_left && !has_top) {
            Mem.set(left_column, 16, (1 << bitdepth - 1) + 1, left_size);
         } else {
            int left_limit = Math.min(max_y - y + 1, bottom_left_size);

            for (int i = 0; i < left_limit; i++) {
               left_column[16 + i] = bufferData[(y + i) * bufferStride + (x - 1)];
            }

            if (left_size - left_limit > 0) {
               Mem.set(left_column, 16 + left_limit, bufferData[(left_limit + y - 1) * bufferStride + (x - 1)], left_size - left_limit);
            }
         }
      }

      int[] dest = buffer.data_;
      int destPos = y * buffer.columns() + x;
      int dest_stride = buffer.columns();
      if (use_filter_intra) {
         Intra.filterIntraPredictor(dest, destPos, dest_stride, top_row, 16, left_column, 16, prediction_parameters.filter_intra_mode, width, height);
      } else if (is_directional_mode) {
         this.directionalPrediction(
            block, plane, x, y, has_left, has_top, needs_left, needs_top, prediction_angle, width, height, max_x, max_y, tx_size, top_row, 16, left_column, 16
         );
      } else {
         int predictor = getIntraPredictor(mode, has_left, has_top);
         Intra.doPrediction(tx_size, predictor, dest, destPos, dest_stride, top_row, 16, left_column, 16);
      }
   }

   private static int getIntraEdgeFilterType(Block block, int plane) {
      boolean top;
      boolean left;
      if (plane == 0) {
         top = block.topAvail[0] && D.kPredictionModeSmoothMask.Contains(block.bp_top.y_mode);
         left = block.leftAvail[0] && D.kPredictionModeSmoothMask.Contains(block.bp_left.y_mode);
      } else {
         top = block.topAvail[plane] && block.bp.prediction_parameters.chroma_top_uses_smooth_prediction;
         left = block.leftAvail[plane] && block.bp.prediction_parameters.chroma_left_uses_smooth_prediction;
      }

      return !top && !left ? 0 : 1;
   }

   private void directionalPrediction(
      Block block,
      int plane,
      int x,
      int y,
      boolean hasLeft,
      boolean hasTop,
      boolean needsLeft,
      boolean needsTop,
      int predictionAngle,
      int width,
      int height,
      int maxX,
      int maxY,
      int txSize,
      int[] topRow,
      int topRowPos,
      int[] leftColumn,
      int leftColomnPos
   ) {
      D.Array2DView buffer = this.buffer_[plane];
      int[] dest = buffer.data_;
      int destPos = y * buffer.columns() + x;
      int stride = buffer.columns();
      if (predictionAngle == 90) {
         Intra.doPrediction(txSize, 4, dest, destPos, stride, topRow, topRowPos, leftColumn, leftColomnPos);
      } else if (predictionAngle == 180) {
         Intra.doPrediction(txSize, 5, dest, destPos, stride, topRow, topRowPos, leftColumn, leftColomnPos);
      } else {
         boolean upsampled_top = false;
         boolean upsampled_left = false;
         if (this.sequence_header_.enable_intra_edge_filter) {
            int filter_type = getIntraEdgeFilterType(block, plane);
            if (predictionAngle > 90 && predictionAngle < 180 && width + height >= 24) {
               leftColumn[leftColomnPos - 1] = topRow[topRowPos - 1] = D.RightShiftWithRounding(
                  leftColumn[leftColomnPos + 0] * 5 + topRow[topRowPos - 1] * 6 + topRow[topRowPos + 0] * 5, 4
               );
            }

            if (hasTop && needsTop) {
               int strength = getIntraEdgeFilterStrength(width, height, filter_type, predictionAngle - 90);
               if (strength > 0) {
                  int num_pixels = Math.min(width, maxX - x + 1) + (predictionAngle < 90 ? height : 0) + 1;
                  Intra.edgeFilter(topRow, topRowPos - 1, num_pixels, strength);
               }
            }

            if (hasLeft && needsLeft) {
               int strength = getIntraEdgeFilterStrength(width, height, filter_type, predictionAngle - 180);
               if (strength > 0) {
                  int num_pixels = Math.min(height, maxY - y + 1) + (predictionAngle > 180 ? width : 0) + 1;
                  Intra.edgeFilter(leftColumn, leftColomnPos - 1, num_pixels, strength);
               }
            }

            upsampled_top = doIntraEdgeUpsampling(width, height, filter_type, predictionAngle - 90);
            if (upsampled_top && needsTop) {
               int num_pixels = width + (predictionAngle < 90 ? height : 0);
               Intra.edgeUpsampler(topRow, topRowPos, num_pixels);
            }

            upsampled_left = doIntraEdgeUpsampling(width, height, filter_type, predictionAngle - 180);
            if (upsampled_left && needsLeft) {
               int num_pixels = height + (predictionAngle > 180 ? width : 0);
               Intra.edgeUpsampler(leftColumn, leftColomnPos, num_pixels);
            }
         }

         if (predictionAngle < 90) {
            int dx = getDirectionalIntraPredictorDerivative(predictionAngle);
            Intra.predDirectionalZone1(dest, destPos, stride, topRow, topRowPos, width, height, dx, upsampled_top);
         } else if (predictionAngle < 180) {
            int dx = getDirectionalIntraPredictorDerivative(180 - predictionAngle);
            int dy = getDirectionalIntraPredictorDerivative(predictionAngle - 90);
            Intra.predDirectionalZone2(
               dest, destPos, stride, topRow, topRowPos, leftColumn, leftColomnPos, width, height, dx, dy, upsampled_top, upsampled_left
            );
         } else {
            int dy = getDirectionalIntraPredictorDerivative(270 - predictionAngle);
            Intra.predDirectionalZone3(dest, destPos, stride, leftColumn, leftColomnPos, width, height, dy, upsampled_left);
         }
      }
   }

   private void palettePrediction(Block block, int plane, int startX, int startY, int x, int y, int txSize) {
      int txWidth = D.kTransformWidth[txSize];
      int txHeight = D.kTransformHeight[txSize];
      int[] palette = block.bp.prediction_parameters.palette_mode_info.color[plane];
      int planeType = D.GetPlaneType(plane);
      int x4 = x * 4;
      int y4 = y * 4;
      D.Array2DView buffer = this.buffer_[plane];

      for (int row = 0; row < txHeight; row++) {
         for (int column = 0; column < txWidth; column++) {
            int val = palette[block.bp.prediction_parameters.color_index_map[planeType][y4 + row][x4 + column]];
            buffer.set(startY + row, startX + column, val);
         }
      }
   }

   private void chromaFromLumaPrediction(Block block, int plane, int startX, int startY, int txSize) {
      int subsampling_x = this.subsampling_x_[plane];
      int subsampling_y = this.subsampling_y_[plane];
      D.PredictionParams prediction_parameters = block.bp.prediction_parameters;
      D.Array2DView y_buffer = this.buffer_[0];
      if (!block.scratch_buffer.cfl_luma_buffer_valid) {
         int luma_x = startX << subsampling_x;
         int luma_y = startY << subsampling_y;
         int[] source = y_buffer.data_;
         int sourcePos = luma_y * y_buffer.columns() + luma_x;
         Intra.doCflSubsampler(
            txSize,
            subsampling_x + subsampling_y,
            block.scratch_buffer.cfl_luma_buffer,
            prediction_parameters.max_luma_width - luma_x,
            prediction_parameters.max_luma_height - luma_y,
            source,
            sourcePos,
            y_buffer.columns()
         );
         block.scratch_buffer.cfl_luma_buffer_valid = true;
      }

      D.Array2DView buffer = this.buffer_[plane];
      int[] dest = buffer.data_;
      int destPos = startY * buffer.columns() + startX;
      Intra.doCflIntraPredictor(
         txSize,
         dest,
         destPos,
         buffer.columns(),
         block.scratch_buffer.cfl_luma_buffer,
         plane == 1 ? prediction_parameters.cfl_alpha_u : prediction_parameters.cfl_alpha_v
      );
   }

   static void lowerMvPrecision(D.ObuFrameHeader frameHeader, D.MotionVector mvs) {
      if (!frameHeader.allow_high_precision_mv) {
         if (frameHeader.force_integer_mv != 0) {
            for (int i = 0; i < mvs.mv.length; i++) {
               int mv = mvs.mv[i];
               mv = mv + 3 - (mv >> 15) & -8;
               mvs.mv[i] = mv;
            }
         } else {
            for (int i = 0; i < mvs.mv.length; i++) {
               int mv = mvs.mv[i];
               mv = mv - (mv >> 15) & -2;
               mvs.mv[i] = mv;
            }
         }
      }
   }

   static void setupGlobalMv(Block block, int index, D.MotionVector mv) {
      D.BlockParams bp = block.bp;
      D.ObuFrameHeader frame_header = block.tile.frame_header();
      int reference_type = bp.reference_frame[index];
      D.GlobalMotion gm = frame_header.global_motion[reference_type];
      if (reference_type == 0 || gm.type == 0) {
         mv.mv32 = 0;
      } else if (gm.type != 1) {
         int x = block.column4x4 * 4 + block.width / 2 - 1;
         int y = block.row4x4 * 4 + block.height / 2 - 1;
         int xc = (gm.params[2] - 65536) * x + gm.params[3] * y + gm.params[0];
         int yc = gm.params[4] * x + (gm.params[5] - 65536) * y + gm.params[1];
         if (frame_header.allow_high_precision_mv) {
            mv.mv[0] = D.RightShiftWithRoundingSigned(yc, 13);
            mv.mv[1] = D.RightShiftWithRoundingSigned(xc, 13);
         } else {
            mv.mv[0] = 2 * D.RightShiftWithRoundingSigned(yc, 14);
            mv.mv[1] = 2 * D.RightShiftWithRoundingSigned(xc, 14);
            lowerMvPrecision(frame_header, mv);
         }
      } else {
         for (int i = 0; i < 2; i++) {
            mv.mv[i] = gm.params[i] >> 13;
         }

         lowerMvPrecision(frame_header, mv);
      }
   }

   static boolean isGlobalMvBlock(D.BlockParams bp, int type) {
      return (bp.y_mode == 16 || bp.y_mode == 24) && !D.IsBlockDimension4(bp.size) && type > 1;
   }

   static void searchStack(Block block, D.BlockParams mv_bp, int index, int weight, boolean[] foundNewMv, boolean[] foundMatch, int[] numMvFound) {
      D.BlockParams bp = block.bp;
      D.GlobalMotion[] global_motion = block.tile.frame_header().global_motion;
      D.PredictionParams prediction_parameters = bp.prediction_parameters;
      int global_motion_type = global_motion[bp.reference_frame[0]].type;
      D.MotionVector candidate_mv;
      if (isGlobalMvBlock(mv_bp, global_motion_type)) {
         candidate_mv = prediction_parameters.global_mv[0];
      } else {
         candidate_mv = mv_bp.mv.mv[index];
      }

      foundNewMv[0] |= kPredictionModeNewMvMask.Contains(mv_bp.y_mode);
      foundMatch[0] = true;
      D.MotionVector[] ref_mv_stack = prediction_parameters.ref_mv_stack;
      int num_found = numMvFound[0];
      if (num_found < 8) {
         ref_mv_stack[num_found] = candidate_mv;
         prediction_parameters.SetWeightIndexStackEntry(num_found, weight);
         numMvFound[0]++;
      }
   }

   static void compoundSearchStack(Block block, D.BlockParams mv_bp, int weight, boolean[] foundNewMv, boolean[] foundMatch, int[] numMvFound) {
      D.BlockParams bp = block.bp;
      D.GlobalMotion[] global_motion = block.tile.frame_header().global_motion;
      D.PredictionParams prediction_parameters = bp.prediction_parameters;
      D.CompoundMotionVector candidate_mv = mv_bp.mv;

      for (int i = 0; i < 2; i++) {
         int global_motion_type = global_motion[bp.reference_frame[i]].type;
         if (isGlobalMvBlock(mv_bp, global_motion_type)) {
            candidate_mv.mv[i] = prediction_parameters.global_mv[i];
         }
      }

      foundNewMv[0] |= kPredictionModeNewMvMask.Contains(mv_bp.y_mode);
      foundMatch[0] = true;
      D.CompoundMotionVector[] compound_ref_mv_stack = prediction_parameters.compound_ref_mv_stack;
      int num_found = numMvFound[0];
      if (num_found < 8) {
         compound_ref_mv_stack[num_found].mv64 = candidate_mv.mv64;
         prediction_parameters.SetWeightIndexStackEntry(num_found, weight);
         numMvFound[0]++;
      }
   }

   static void addReferenceMvCandidate(
      Block block, D.BlockParams mvBp, boolean isCompound, int weight, boolean[] foundNewMv, boolean[] foundMatch, int[] numMvFound
   ) {
      if (mvBp.is_inter) {
         D.BlockParams bp = block.bp;
         if (isCompound) {
            if (mvBp.reference_frame[0] == bp.reference_frame[0] && mvBp.reference_frame[1] == bp.reference_frame[1]) {
               compoundSearchStack(block, mvBp, weight, foundNewMv, foundMatch, numMvFound);
            }
         } else {
            for (int i = 0; i < 2; i++) {
               if (mvBp.reference_frame[i] == bp.reference_frame[0]) {
                  searchStack(block, mvBp, i, weight, foundNewMv, foundMatch, numMvFound);
               }
            }
         }
      }
   }

   private static int getMinimumStep(int blockWidthOrHeight4X4, int deltaRowOrColumn) {
      if (blockWidthOrHeight4X4 >= 16) {
         return 4;
      } else {
         return deltaRowOrColumn < -1 ? 2 : 0;
      }
   }

   static void scanRow(Block block, int mvColumn, int deltaRow, boolean isCompound, boolean[] foundNewMv, boolean[] foundMatch, int[] numMvFound) {
      int mv_row = block.row4x4 + deltaRow;
      Tile tile = block.tile;
      if (tile.IsTopInside(mv_row + 1)) {
         int width4x4 = block.width4x4;
         int min_step = getMinimumStep(width4x4, deltaRow);
         D.BlockParams bps = tile.BlockParametersAddress(mv_row, mvColumn);
         int bpsPos = bps.pos;
         int end_bps = bpsPos + Math.min(width4x4, Math.min(tile.frame_header().columns4x4 - block.column4x4, 16));

         do {
            D.BlockParams mv_bp = tile.findGivenBlockParameters(bpsPos);
            int step = Math.max(Math.min(width4x4, D.kNum4x4BlocksWide[mv_bp.size]), min_step);
            addReferenceMvCandidate(block, mv_bp, isCompound, step * 2, foundNewMv, foundMatch, numMvFound);
            bpsPos += step;
         } while (bpsPos < end_bps);
      }
   }

   static void scanColumn(
      Block block, int mv_row, int delta_column, boolean is_compound, boolean[] found_new_mv, boolean[] found_match, int[] num_mv_found
   ) {
      int mv_column = block.column4x4 + delta_column;
      Tile tile = block.tile;
      if (tile.IsLeftInside(mv_column + 1)) {
         int height4x4 = block.height4x4;
         int min_step = getMinimumStep(height4x4, delta_column);
         int stride = tile.BlockParametersStride();
         D.BlockParams bps = tile.BlockParametersAddress(mv_row, mv_column);
         int bpsPos = bps.pos;
         int end_bps = bpsPos + stride * Math.min(height4x4, Math.min(tile.frame_header().rows4x4 - block.row4x4, 16));

         do {
            D.BlockParams mv_bp = tile.findGivenBlockParameters(bpsPos);
            int step = Math.max(Math.min(height4x4, D.kNum4x4BlocksHigh[mv_bp.size]), min_step);
            addReferenceMvCandidate(block, mv_bp, is_compound, step * 2, found_new_mv, found_match, num_mv_found);
            bpsPos += step * stride;
         } while (bpsPos < end_bps);
      }
   }

   static void scanPoint(Block block, int deltaRow, int deltaColumn, boolean isCompound, boolean[] foundNewMv, boolean[] foundMatch, int[] numMvFound) {
      int mv_row = block.row4x4 + deltaRow;
      int mv_column = block.column4x4 + deltaColumn;
      Tile tile = block.tile;
      if (tile.IsInside(mv_row, mv_column) && tile.HasParameters(mv_row, mv_column)) {
         D.BlockParams mv_bp = tile.Parameters(mv_row, mv_column);
         if (mv_bp.reference_frame[0] != -1) {
            addReferenceMvCandidate(block, mv_bp, isCompound, 4, foundNewMv, foundMatch, numMvFound);
         }
      }
   }

   static boolean isWithinTheSame64X64Block(Block block, int deltaRow, int deltaColumn) {
      int row = (block.row4x4 & 15) + deltaRow;
      int column = (block.column4x4 & 15) + deltaColumn;
      return row < 16 && column >= 0 && column < 16;
   }

   static void addExtraCompoundMvCandidate(
      Block block, int mvRow, int mvColumn, int[] refIdCount, D.MotionVector[][] refId, int[] refDiffCount, D.MotionVector[][] refDiff
   ) {
      D.BlockParams bp = block.tile.Parameters(mvRow, mvColumn);
      boolean[] reference_frame_sign_bias = block.tile.reference_frame_sign_bias_;

      for (int i = 0; i < 2; i++) {
         int candidate_reference_frame = bp.reference_frame[i];
         if (candidate_reference_frame > 0) {
            for (int j = 0; j < 2; j++) {
               D.MotionVector candidate_mv = bp.mv.mv[i];
               int block_reference_frame = block.bp.reference_frame[j];
               if (candidate_reference_frame == block_reference_frame && refIdCount[j] < 2) {
                  refId[j][refIdCount[j]] = candidate_mv;
                  refIdCount[j]++;
               } else if (refDiffCount[j] < 2) {
                  if (reference_frame_sign_bias[candidate_reference_frame] != reference_frame_sign_bias[block_reference_frame]) {
                     candidate_mv.mv[0] = candidate_mv.mv[0] * -1;
                     candidate_mv.mv[1] = candidate_mv.mv[1] * -1;
                  }

                  refDiff[j][refDiffCount[j]] = candidate_mv;
                  refDiffCount[j]++;
               }
            }
         }
      }
   }

   static class Block {
      final Tile tile;
      final boolean hasChroma;
      final boolean[] topAvail = new boolean[3];
      final boolean[] leftAvail = new boolean[3];
      final int[] residual_size = new int[3];
      final int[] residual;
      int residualPos;
      final int row4x4;
      final int column4x4;
      final int width;
      final int height;
      final int width4x4;
      final int height4x4;
      final int size;
      final int top_context_index;
      final int left_context_index;
      D.BlockParams bp_top;
      D.BlockParams bp_left;
      D.BlockParams bp;
      final D.TileScratchBuffer scratch_buffer;
      final D.BlockCdfContext top_context;

      Block(Tile tile, int size, int row4x4, int column4x4, D.TileScratchBuffer scratch_buffer, int[] residual, int residualPos) {
         this.tile = tile;
         this.size = size;
         this.row4x4 = row4x4;
         this.column4x4 = column4x4;
         this.width = D.kBlockWidthPixels[size];
         this.height = D.kBlockHeightPixels[size];
         this.width4x4 = this.width >> 2;
         this.height4x4 = this.height >> 2;
         this.scratch_buffer = scratch_buffer;
         this.residual = residual;
         this.residualPos = residualPos;
         this.top_context = tile.top_context_.get()[tile.SuperBlockColumnIndex(column4x4)];
         this.top_context_index = tile.CdfContextIndex(column4x4);
         this.left_context_index = tile.CdfContextIndex(row4x4);
         this.residual_size[0] = D.kPlaneResidualSize[size][0][0];
         this.residual_size[1] = this.residual_size[2] = D.kPlaneResidualSize[size][tile.subsampling_x_[1]][tile.subsampling_y_[1]];
         if ((row4x4 & 1) == 0 && (tile.sequence_header_.color_config.subsampling_y & this.height4x4) == 1) {
            this.hasChroma = false;
         } else if ((column4x4 & 1) == 0 && (tile.sequence_header_.color_config.subsampling_x & this.width4x4) == 1) {
            this.hasChroma = false;
         } else {
            this.hasChroma = !tile.sequence_header_.color_config.is_monochrome;
         }

         this.topAvail[0] = tile.IsTopInside(row4x4);
         this.leftAvail[0] = tile.IsLeftInside(column4x4);
         if (this.hasChroma) {
            this.topAvail[1] = this.topAvail[2] = tile.IsTopInside(row4x4 - (tile.sequence_header_.color_config.subsampling_y & this.height4x4));
            this.leftAvail[1] = this.leftAvail[2] = tile.IsLeftInside(column4x4 - (tile.sequence_header_.color_config.subsampling_x & this.width4x4));
         }

         this.bp = tile.BlockParametersAddress(row4x4, column4x4);
         if (this.topAvail[0]) {
            this.bp_top = tile.BlockParametersAddress(row4x4 - 1, column4x4);
         }

         if (this.leftAvail[0]) {
            this.bp_left = tile.BlockParametersAddress(row4x4, column4x4 - 1);
         }
      }

      boolean HasChroma() {
         return this.hasChroma;
      }

      int TopReference(int index) {
         return this.bp_top.reference_frame[index];
      }

      int LeftReference(int index) {
         return this.bp_left.reference_frame[index];
      }

      boolean IsTopIntra() {
         return this.TopReference(0) <= 0;
      }

      boolean IsLeftIntra() {
         return this.LeftReference(0) <= 0;
      }

      boolean IsTopSingle() {
         return this.TopReference(1) <= 0;
      }

      boolean IsLeftSingle() {
         return this.LeftReference(1) <= 0;
      }

      int CountReferences(int type) {
         int a = this.topAvail[0] && this.bp_top.reference_frame[0] == type ? 1 : 0;
         int b = this.topAvail[0] && this.bp_top.reference_frame[1] == type ? 1 : 0;
         int c = this.leftAvail[0] && this.bp_left.reference_frame[0] == type ? 1 : 0;
         int d = this.leftAvail[0] && this.bp_left.reference_frame[1] == type ? 1 : 0;
         return a + b + c + d;
      }

      public boolean HasOverlappableCandidates() {
         LogWriter.writeLog("Tile Error: has overlappable candidates not to be called");
         return false;
      }
   }
}
