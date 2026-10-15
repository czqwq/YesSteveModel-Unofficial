package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class PostFilter {
   static final int kStep = 8;
   static final int kStep4x4 = 2;
   boolean debug;
   Yuv frame_buffer_ = new Yuv();
   Yuv cdef_border_ = new Yuv();
   int bitdepth_;
   int[] subsampling_x_ = new int[3];
   int[] subsampling_y_ = new int[3];
   int planes_;
   int pixel_size_log2_;
   int[] inner_thresh_;
   int[] outer_thresh_;
   boolean needs_chroma_deblock_;
   boolean do_cdef_;
   boolean do_deblock_;
   boolean do_restoration_;
   boolean do_superres_;
   D.ObuFrameHeader frame_header_ = new D.ObuFrameHeader();
   int[][] source_buffer_ = new int[3][];
   int[][] cdef_buffer_ = new int[3][];
   int[][] superres_buffer_ = new int[3][];
   int[][] loop_restoration_buffer_ = new int[3][];
   int[] source_buffer_pos = new int[3];
   int[] cdef_buffer_pos = new int[3];
   int[] superres_buffer_pos = new int[3];
   D.BlockParamsHolder block_parameters_;
   D.Array2DView cdef_index_;
   D.Array2DView cdef_skip_;
   int[][] inter_transform_sizes_;
   D.LoopRestorationInfo restoration_info_ = new D.LoopRestorationInfo();
   int[] superres_coefficients_ = new int[2];
   Yuv superres_line_buffer_ = new Yuv();
   Yuv cdef_filtered_buffer_ = new Yuv();
   D.LoopRestoration loop_restoration_;
   Yuv loop_restoration_border_ = new Yuv();
   int progress_row_ = -1;
   int[] cdef_block_ = new int[9248];
   int[][][][] deblock_filter_levels_ = new int[8][4][8][2];
   D.ThreadPool thread_pool_ = null;

   Yuv frame_buffer() {
      return this.frame_buffer_;
   }

   int[] GetUnfilteredBuffer(int plane) {
      return this.source_buffer_[plane];
   }

   int GetBufferOffset(int stride, int plane, int row, int column) {
      return (row >> this.subsampling_y_[plane]) * stride + (column >> this.subsampling_x_[plane] << this.pixel_size_log2_);
   }

   static boolean DoCdef(D.ObuFrameHeader frame_header, int do_post_filter_mask) {
      return (
            frame_header.cdef.bits > 0
               || frame_header.cdef.y_primary_strength[0] > 0
               || frame_header.cdef.y_secondary_strength[0] > 0
               || frame_header.cdef.uv_primary_strength[0] > 0
               || frame_header.cdef.uv_secondary_strength[0] > 0
         )
         && (do_post_filter_mask & 2) != 0;
   }

   boolean DoCdef() {
      return this.do_cdef_;
   }

   static boolean DoDeblock(D.ObuFrameHeader frame_header, int do_post_filter_mask) {
      return (frame_header.loop_filter.level[0] > 0 || frame_header.loop_filter.level[1] > 0) && (do_post_filter_mask & 1) != 0;
   }

   boolean DoDeblock() {
      return this.do_deblock_;
   }

   int GetZeroDeltaDeblockFilterLevel(int segment_id, int level_index, int type, int mode_id) {
      return this.deblock_filter_levels_[segment_id][level_index][type][mode_id];
   }

   static boolean DoRestoration(D.LoopRestoration loop_restoration, int do_post_filter_mask, int num_planes) {
      return num_planes == 1
         ? loop_restoration.type[0] != 0 && (do_post_filter_mask & 8) != 0
         : (loop_restoration.type[0] != 0 || loop_restoration.type[1] != 0 || loop_restoration.type[2] != 0) && (do_post_filter_mask & 8) != 0;
   }

   boolean DoRestoration() {
      return this.do_restoration_;
   }

   static boolean DoSuperRes(D.ObuFrameHeader frame_header, int do_post_filter_mask) {
      return frame_header.width != frame_header.upscaled_width && (do_post_filter_mask & 4) != 0;
   }

   boolean DoSuperRes() {
      return this.do_superres_;
   }

   static void CopyPixels(int[] src, int srcPos, int src_stride, int[] dst, int dstPos, int dst_stride, int width, int height, int pixel_size) {
      int y = height;

      do {
         Mem.cpy(dst, dstPos, src, srcPos, width * pixel_size);
         srcPos += src_stride;
         dstPos += dst_stride;
      } while (--y != 0);
   }

   PostFilter(
      D.ObuFrameHeader frame_header, D.ObuSequenceHeader sequence_header, D.FrameScratchBuffer frame_scratch_buffer, Yuv frame_buffer, int do_post_filter_mask
   ) {
      this.frame_header_ = frame_header;
      this.loop_restoration_ = frame_header.loop_restoration;
      this.bitdepth_ = sequence_header.color_config.bitdepth;
      this.subsampling_x_ = new int[]{0, sequence_header.color_config.subsampling_x, sequence_header.color_config.subsampling_x};
      this.subsampling_y_ = new int[]{0, sequence_header.color_config.subsampling_y, sequence_header.color_config.subsampling_y};
      this.planes_ = sequence_header.color_config.is_monochrome ? 1 : 3;
      this.pixel_size_log2_ = 1;
      this.inner_thresh_ = D.kInnerThresh[frame_header.loop_filter.sharpness];
      this.outer_thresh_ = D.kOuterThresh[frame_header.loop_filter.sharpness];
      this.needs_chroma_deblock_ = frame_header.loop_filter.level[2] != 0 || frame_header.loop_filter.level[3] != 0;
      this.do_cdef_ = DoCdef(frame_header, do_post_filter_mask);
      this.do_deblock_ = DoDeblock(frame_header, do_post_filter_mask);
      this.do_restoration_ = DoRestoration(this.loop_restoration_, do_post_filter_mask, this.planes_);
      this.do_superres_ = DoSuperRes(frame_header, do_post_filter_mask);
      this.cdef_index_ = frame_scratch_buffer.cdef_index;
      this.cdef_skip_ = frame_scratch_buffer.cdef_skip;
      this.inter_transform_sizes_ = frame_scratch_buffer.inter_transform_sizes;
      this.restoration_info_ = frame_scratch_buffer.loop_restoration_info;
      this.superres_coefficients_ = new int[]{
         frame_scratch_buffer.superres_coefficients[0],
         frame_scratch_buffer.superres_coefficients[!sequence_header.color_config.is_monochrome && sequence_header.color_config.subsampling_x != 0 ? 1 : 0]
      };
      this.superres_line_buffer_ = frame_scratch_buffer.superres_line_buffer;
      this.block_parameters_ = frame_scratch_buffer.block_parameters_holder;
      this.frame_buffer_ = frame_buffer;
      this.cdef_border_ = frame_scratch_buffer.cdef_border;
      this.loop_restoration_border_ = frame_scratch_buffer.loop_restoration_border;
      int[] zero_delta_lf = new int[4];
      this.ComputeDeblockFilterLevels(zero_delta_lf, this.deblock_filter_levels_);
      if (this.DoSuperRes()) {
         LogWriter.writeLog("PostFilter superres not supported");
      }

      int plane = 0;

      do {
         this.loop_restoration_buffer_[plane] = this.frame_buffer_.data(plane);
         this.cdef_buffer_[plane] = this.frame_buffer_.data(plane);
         this.superres_buffer_[plane] = this.frame_buffer_.data(plane);
         this.source_buffer_[plane] = this.frame_buffer_.data(plane);
      } while (++plane < this.planes_);

      if (this.DoCdef() || this.DoRestoration() || this.DoSuperRes()) {
         plane = 0;
         int pixel_size_log2 = this.pixel_size_log2_;

         do {
            int horizontal_shift = 0;
            int vertical_shift = 0;
            if (this.DoRestoration() && this.loop_restoration_.type[plane] != 0) {
               horizontal_shift += this.frame_buffer_.alignment();
               if (!this.DoCdef() && this.thread_pool_ == null) {
                  vertical_shift += 2;
               }

               this.superres_buffer_pos[plane] = this.superres_buffer_pos[plane]
                  + vertical_shift * this.frame_buffer_.stride(plane)
                  + (horizontal_shift << pixel_size_log2);
            }

            if (this.DoSuperRes()) {
               vertical_shift++;
            }

            this.cdef_buffer_pos[plane] = this.cdef_buffer_pos[plane]
               + vertical_shift * this.frame_buffer_.stride(plane)
               + (horizontal_shift << pixel_size_log2);
            if (this.DoCdef() && this.thread_pool_ == null) {
               horizontal_shift += this.frame_buffer_.alignment();
               vertical_shift += 2;
            }

            this.source_buffer_pos[plane] = this.source_buffer_pos[plane]
               + vertical_shift * this.frame_buffer_.stride(plane)
               + (horizontal_shift << pixel_size_log2);
         } while (++plane < this.planes_);
      }
   }

   static void ExtendFrame(int[] frame_start, int frame_startPos, int width, int height, int stride, int left, int right, int top, int bottom) {
      int[] src = frame_start;
      int srcPos = frame_startPos;
      int y = height;

      do {
         D.ExtendLine(src, srcPos, width, left, right);
         srcPos += stride;
      } while (--y != 0);

      int[] dst = src;
      int dstPos = srcPos - left;
      src = src;
      srcPos = dstPos - stride;

      for (int yy = 0; yy < bottom; yy++) {
         Mem.cpy(dst, dstPos, src, srcPos, stride);
         dstPos += stride;
      }

      src = frame_start;
      srcPos = frame_startPos - left;
      dst = frame_start;
      dstPos = frame_startPos - left - top * stride;

      for (int yy = 0; yy < top; yy++) {
         if (srcPos >= 0 && dstPos >= 0) {
            Mem.cpy(dst, dstPos, src, srcPos, stride);
         }

         dstPos += stride;
      }
   }

   static void ExtendFrameBoundary(int[] frame_start, int frame_startPos, int width, int height, int stride, int left, int right, int top, int bottom) {
      ExtendFrame(frame_start, frame_startPos, width, height, stride, left, right, top, bottom);
   }

   void ExtendBordersForReferenceFrame() {
      if (this.frame_header_.refresh_frame_flags != 0) {
         int upscaled_width = this.frame_header_.upscaled_width;
         int height = this.frame_header_.height;
         int plane = 0;

         do {
            int plane_width = SubsampledValue(upscaled_width, this.subsampling_x_[plane]);
            int plane_height = SubsampledValue(height, this.subsampling_y_[plane]);
            ExtendFrameBoundary(
               this.frame_buffer_.data(plane),
               0,
               plane_width,
               plane_height,
               this.frame_buffer_.stride(plane),
               this.frame_buffer_.leftBorder(plane),
               this.frame_buffer_.rightBorder(plane),
               this.frame_buffer_.topBorder(plane),
               this.frame_buffer_.bottomBorder(plane)
            );
         } while (++plane < this.planes_);
      }
   }

   void CopyDeblockedPixels(int plane, int row4x4) {
      int src_stride = this.frame_buffer_.stride(plane);
      int[] src = this.source_buffer_[plane];
      int srcPos = this.source_buffer_pos[plane] + this.GetBufferOffset(src_stride, plane, row4x4, 0);
      int row_offset = row4x4 / 4;
      int dst_stride = this.loop_restoration_border_.stride(plane);
      int[] dst = this.loop_restoration_border_.data(plane);
      int dstPos = row_offset * dst_stride;
      int num_pixels = SubsampledValue(this.frame_header_.columns4x4 * 4, this.subsampling_x_[plane]);
      int row_width = num_pixels << this.pixel_size_log2_;
      int last_valid_row = -1;
      int plane_height = SubsampledValue(this.frame_header_.height, this.subsampling_y_[plane]);
      int row = D.kLoopRestorationBorderRows[this.subsampling_y_[plane]];
      int absolute_row = (row4x4 * 4 >> this.subsampling_y_[plane]) + row;

      for (int i = 0; i < 4; row++) {
         if (absolute_row + i >= plane_height) {
            if (last_valid_row == -1) {
               break;
            }

            row = last_valid_row;
         }

         Mem.cpy(dst, dstPos, src, srcPos + row * src_stride, row_width);
         last_valid_row = row;
         dstPos += dst_stride;
         i++;
      }
   }

   void CopyBordersForOneSuperBlockRow(int row4x4, int sb4x4, boolean for_loop_restoration) {
      int row_offset = row4x4 == 0 ? 0 : 8;
      int height_offset = row4x4 == 0 ? 8 : 0;
      int extra_rows = for_loop_restoration && this.thread_pool_ == null && !this.DoCdef() ? 2 : 0;
      int upscaled_width = this.frame_header_.upscaled_width;
      int height = this.frame_header_.height;
      int plane = 0;

      do {
         int plane_width = SubsampledValue(upscaled_width, this.subsampling_x_[plane]);
         int plane_height = SubsampledValue(height, this.subsampling_y_[plane]);
         int row = row4x4 * 4 - row_offset >> this.subsampling_y_[plane];
         if (row >= plane_height) {
            break;
         }

         int num_rows = Math.min(SubsampledValue(sb4x4 * 4 - height_offset, this.subsampling_y_[plane]) + extra_rows, plane_height - row);
         if (!for_loop_restoration && plane == 0) {
            this.progress_row_ = row + num_rows;
         }

         boolean copy_bottom = row + num_rows == plane_height;
         int stride = this.frame_buffer_.stride(plane);
         int startPos = row * stride;
         int[] start;
         if (for_loop_restoration) {
            start = this.superres_buffer_[plane];
            startPos += this.superres_buffer_pos[plane];
         } else {
            start = this.frame_buffer_.data(plane);
         }

         int left_border = for_loop_restoration ? 4 : this.frame_buffer_.leftBorder(plane);
         int right_border = for_loop_restoration ? 4 : this.frame_buffer_.rightBorder(plane);
         int top_border = row == 0 ? (for_loop_restoration ? 2 : this.frame_buffer_.topBorder(plane)) : 0;
         int bottom_border = copy_bottom ? (for_loop_restoration ? 2 : this.frame_buffer_.bottomBorder(plane)) : 0;
         ExtendFrameBoundary(start, startPos, plane_width, num_rows, stride, left_border, right_border, top_border, bottom_border);
      } while (++plane < this.planes_);
   }

   void SetupLoopRestorationBorder(int row4x4) {
      int upscaled_width = this.frame_header_.upscaled_width;
      int height = this.frame_header_.height;
      int plane = 0;

      do {
         if (this.loop_restoration_.type[plane] != 0) {
            int row_offset = row4x4 / 4;
            int num_pixels = SubsampledValue(upscaled_width, this.subsampling_x_[plane]);
            int row_width = num_pixels << this.pixel_size_log2_;
            int plane_height = SubsampledValue(height, this.subsampling_y_[plane]);
            int row = D.kLoopRestorationBorderRows[this.subsampling_y_[plane]];
            int absolute_row = (row4x4 * 4 >> this.subsampling_y_[plane]) + row;
            int src_stride = this.frame_buffer_.stride(plane);
            int[] src = this.superres_buffer_[plane];
            int srcOffset = this.superres_buffer_pos[plane] + this.GetBufferOffset(src_stride, plane, row4x4, 0);
            int srcPos = srcOffset + row * src_stride;
            int dst_stride = this.loop_restoration_border_.stride(plane);
            int[] dst = this.loop_restoration_border_.data(plane);
            int dstPos = row_offset * dst_stride;

            for (int i = 0; i < 4; i++) {
               Mem.cpy(dst, dstPos, src, srcPos, row_width);
               D.ExtendLine(dst, dstPos, num_pixels, 4, 4);
               if (absolute_row + i < plane_height - 1) {
                  srcPos += src_stride;
               }

               dstPos += dst_stride;
            }
         }
      } while (++plane < this.planes_);
   }

   void SetupLoopRestorationBorder(int row4x4_start, int sb4x4) {
      for (int sb_y = 0; sb_y < sb4x4; sb_y += 16) {
         int row4x4 = row4x4_start + sb_y;
         int row_offset_start = row4x4 / 4;
         int[][] dst = new int[3][];
         int[] dstPos = new int[3];
         dst[0] = this.loop_restoration_border_.data(0);
         dstPos[0] = row_offset_start * this.loop_restoration_border_.stride(0);
         dst[1] = this.loop_restoration_border_.data(1);
         dstPos[1] = row_offset_start * this.loop_restoration_border_.stride(1);
         dst[2] = this.loop_restoration_border_.data(2);
         dstPos[2] = row_offset_start * this.loop_restoration_border_.stride(2);
         if (this.DoSuperRes()) {
            LogWriter.writeLog("do super res in post filter not supported");
         } else {
            int plane = 0;

            do {
               this.CopyDeblockedPixels(plane, row4x4);
            } while (++plane < this.planes_);
         }

         int upscaled_width = this.frame_header_.upscaled_width;
         int plane = 0;

         do {
            if (this.loop_restoration_.type[plane] != 0) {
               int[] dst_line = dst[plane];
               int dst_linePos = dstPos[plane];
               int plane_width = SubsampledValue(upscaled_width, this.subsampling_x_[plane]);

               for (int i = 0; i < 4; i++) {
                  D.ExtendLine(dst_line, dst_linePos, plane_width, 4, 4);
                  dst_linePos += this.loop_restoration_border_.stride(plane);
               }
            }
         } while (++plane < this.planes_);
      }
   }

   static int SubsampledValue(int value, int subsampling) {
      return value + subsampling >> subsampling;
   }

   static void CopyRowForCdef(
      int[] src,
      int srcPos,
      int block_width,
      int unit_width,
      boolean is_frame_left,
      boolean is_frame_right,
      int[] dst,
      int dstPos,
      int[] left_border,
      int leftPos
   ) {
      if (is_frame_left) {
         Mem.set(dst, dstPos - 2, 16384, 2);
      } else if (left_border == null) {
         Mem.cpy(dst, dstPos - 2, src, srcPos - 2, 2);
      } else {
         Mem.cpy(dst, dstPos - 2, left_border, leftPos, 2);
      }

      Mem.cpy(dst, dstPos, src, srcPos, block_width);
      if (is_frame_right) {
         Mem.set(dst, dstPos + block_width, 16384, unit_width + 2 - block_width);
      } else {
         Mem.cpy(dst, dstPos + block_width, src, srcPos + block_width, unit_width + 2 - block_width);
      }
   }

   void SetupCdefBorder(int row4x4) {
      int plane = 0;

      do {
         int src_stride = this.frame_buffer_.stride(plane);
         int dst_stride = this.cdef_border_.stride(plane);
         int row_offset = row4x4 / 4;
         int num_pixels = SubsampledValue(this.frame_header_.columns4x4 * 4, this.subsampling_x_[plane]);
         int row_width = num_pixels << this.pixel_size_log2_;
         int plane_height = SubsampledValue(this.frame_header_.rows4x4 * 4, this.subsampling_y_[plane]);

         for (int i = 0; i < 4; i++) {
            int row = D.kCdefBorderRows[this.subsampling_y_[plane]][i];
            int absolute_row = (row4x4 * 4 >> this.subsampling_y_[plane]) + row;
            if (absolute_row >= plane_height) {
               break;
            }

            int[] src = this.source_buffer_[plane];
            int bOffset = this.GetBufferOffset(this.frame_buffer_.stride(plane), plane, row4x4, 0);
            int srcPos = this.source_buffer_pos[plane] + bOffset + row * src_stride;
            int[] dst = this.cdef_border_.data(plane);
            int dstPos = dst_stride * (row_offset + i);
            Mem.cpy(dst, dstPos, src, srcPos, row_width);
         }
      } while (++plane < this.planes_);
   }

   static int Align(int value, int alignment) {
      int alignment_mask = alignment - 1;
      return value + alignment_mask & ~alignment_mask;
   }

   void PrepareCdefBlock(
      int block_width4x4,
      int block_height4x4,
      int row4x4,
      int column4x4,
      int[] cdef_source,
      int cdef_stride,
      boolean y_plane,
      int[][] border_columns,
      boolean use_border_columns
   ) {
      int max_planes = y_plane ? 1 : 3;
      int subsampling_x = y_plane ? 0 : this.subsampling_x_[1];
      int subsampling_y = y_plane ? 0 : this.subsampling_y_[1];
      int start_x = column4x4 * 4 >> subsampling_x;
      int start_y = row4x4 * 4 >> subsampling_y;
      int plane_width = SubsampledValue(this.frame_header_.width, subsampling_x);
      int plane_height = SubsampledValue(this.frame_header_.height, subsampling_y);
      int block_width = block_width4x4 * 4 >> subsampling_x;
      int block_height = block_height4x4 * 4 >> subsampling_y;
      int unit_width = Align(block_width, 8 >> subsampling_x);
      int unit_height = Align(block_height, 8 >> subsampling_y);
      boolean is_frame_left = column4x4 == 0;
      boolean is_frame_right = start_x + block_width >= plane_width;
      boolean is_frame_top = row4x4 == 0;
      boolean is_frame_bottom = start_y + block_height >= plane_height;
      int y_offset = is_frame_top ? 0 : 2;
      int cdef_border_row_offset = row4x4 / 4 - (is_frame_top ? 0 : 2);

      for (int plane = y_plane ? 0 : 1; plane < max_planes; plane++) {
         int[] cdef_src = cdef_source;
         int cdef_srcPos = (plane == 2 ? 1 : 0) * 68 * 68;
         int src_stride = this.frame_buffer_.stride(plane);
         int[] src_buffer = this.source_buffer_[plane];
         int src_bufferPos = this.source_buffer_pos[plane] + (start_y - y_offset) * src_stride + start_x;
         int cdef_border_stride = this.cdef_border_.stride(plane);
         int[] cdef_border = this.thread_pool_ == null ? null : this.cdef_border_.data(plane);
         int cdef_borderPos = cdef_border_row_offset * cdef_border_stride + start_x;
         cdef_srcPos += 2;
         if (is_frame_top) {
            for (int y = 0; y < 2; y++) {
               Mem.set(cdef_src, cdef_srcPos - 2, 16384, unit_width + 4);
               cdef_srcPos += cdef_stride;
            }
         } else {
            int[] top_border = this.thread_pool_ == null ? src_buffer : cdef_border;
            int top_borderPos = this.thread_pool_ == null ? src_bufferPos : cdef_borderPos;
            int top_border_stride = this.thread_pool_ == null ? src_stride : cdef_border_stride;

            for (int y = 0; y < 2; y++) {
               CopyRowForCdef(top_border, top_borderPos, block_width, unit_width, is_frame_left, is_frame_right, cdef_src, cdef_srcPos, null, 0);
               top_borderPos += top_border_stride;
               cdef_srcPos += cdef_stride;
               src_bufferPos += src_stride;
               cdef_borderPos += cdef_border_stride;
            }
         }

         int y = block_height;
         int y_threshold = this.thread_pool_ != null && !is_frame_bottom ? 2 : 0;
         int[] left_border = this.thread_pool_ != null && use_border_columns ? border_columns[plane] : null;
         int left_borderPos = 0;

         do {
            CopyRowForCdef(
               src_buffer, src_bufferPos, block_width, unit_width, is_frame_left, is_frame_right, cdef_src, cdef_borderPos, left_border, left_borderPos
            );
            cdef_srcPos += cdef_stride;
            src_bufferPos += src_stride;
            if (left_border != null) {
               left_borderPos += 2;
            }
         } while (--y != y_threshold);

         if (y > 0) {
            cdef_borderPos += cdef_border_stride * 2;

            for (int i = 0; i < 2; i++) {
               CopyRowForCdef(cdef_border, cdef_borderPos, block_width, unit_width, is_frame_left, is_frame_right, cdef_src, cdef_srcPos, null, 0);
               cdef_srcPos += cdef_stride;
               cdef_borderPos += cdef_border_stride;
            }
         }

         y = 0;
         if (is_frame_bottom) {
            while (true) {
               Mem.set(cdef_src, cdef_srcPos - 2, 16384, unit_width + 4);
               cdef_srcPos += cdef_stride;
               if (++y >= 2 + unit_height - block_height) {
                  break;
               }
            }
         } else {
            int[] bottom_border = this.thread_pool_ == null ? src_buffer : cdef_border;
            int bottom_borderPos = this.thread_pool_ == null ? src_bufferPos : cdef_borderPos;
            int bottom_border_stride = this.thread_pool_ == null ? src_stride : cdef_border_stride;

            while (true) {
               CopyRowForCdef(bottom_border, bottom_borderPos, block_width, unit_width, is_frame_left, is_frame_right, cdef_src, cdef_srcPos, null, 0);
               bottom_borderPos += bottom_border_stride;
               cdef_srcPos += cdef_stride;
               if (++y >= 2 + unit_height - block_height) {
                  break;
               }
            }
         }
      }
   }

   void ApplyCdefForOneUnit(
      int[] cdef_block,
      int index,
      int block_width4x4,
      int block_height4x4,
      int row4x4_start,
      int column4x4_start,
      int[][][] border_columns,
      boolean[][] use_border_columns
   ) {
      int[] cdef_buffer_row_base_stride = new int[3];
      int[][] cdef_buffer_row_base = new int[3][];
      int[] cdef_buffer_row_baseOffset = new int[3];
      int[] src_buffer_row_base_stride = new int[3];
      int[][] src_buffer_row_base = new int[3][];
      int[] src_buffer_row_baseOffset = new int[3];
      int[][] cdef_src_row_base = new int[3][];
      int[] cdef_src_row_baseOffset = new int[3];
      int[] cdef_src_row_base_stride = new int[3];
      int[] column_step = new int[3];
      int plane = 0;

      do {
         int frameSride = this.frame_buffer_.stride(plane);
         cdef_buffer_row_base[plane] = this.cdef_buffer_[plane];
         cdef_buffer_row_baseOffset[plane] = this.cdef_buffer_pos[plane] + this.GetBufferOffset(frameSride, plane, row4x4_start, column4x4_start);
         cdef_buffer_row_base_stride[plane] = frameSride * (8 >> this.subsampling_y_[plane]);
         src_buffer_row_base[plane] = this.source_buffer_[plane];
         src_buffer_row_baseOffset[plane] = this.source_buffer_pos[plane] + this.GetBufferOffset(frameSride, plane, row4x4_start, column4x4_start);
         src_buffer_row_base_stride[plane] = frameSride * (8 >> this.subsampling_y_[plane]);
         cdef_src_row_base[plane] = cdef_block;
         cdef_src_row_baseOffset[plane] = (plane == 2 ? 1 : 0) * 68 * 68 + 136 + 2;
         cdef_src_row_base_stride[plane] = 68 * (8 >> this.subsampling_y_[plane]);
         column_step[plane] = 8 >> this.subsampling_x_[plane];
      } while (++plane < this.planes_);

      int border_columns_src_index = column4x4_start / 16 & 1;
      int border_columns_dst_index = border_columns_src_index ^ 1;
      if (index == -1) {
         if (this.thread_pool_ == null) {
            int planex = 0;

            do {
               CopyPixels(
                  src_buffer_row_base[planex],
                  src_buffer_row_baseOffset[planex],
                  this.frame_buffer_.stride(planex),
                  cdef_buffer_row_base[planex],
                  cdef_buffer_row_baseOffset[planex],
                  this.frame_buffer_.stride(planex),
                  block_width4x4 * 4 >> this.subsampling_x_[planex],
                  block_height4x4 * 4 >> this.subsampling_y_[planex],
                  1
               );
            } while (++planex < this.planes_);
         }

         use_border_columns[border_columns_dst_index][0] = false;
         use_border_columns[border_columns_dst_index][1] = false;
      } else {
         boolean is_frame_right = (column4x4_start + block_width4x4) * 4 >= this.frame_header_.width;
         if (!is_frame_right && this.thread_pool_ != null) {
            use_border_columns[border_columns_dst_index][0] = true;
            int frame_stride = this.frame_buffer_.stride(0);
            int[] src_line = this.source_buffer_[0];
            int srcOffset = this.source_buffer_pos[0] + this.GetBufferOffset(frame_stride, 0, row4x4_start, column4x4_start + block_width4x4) - 2;
            CopyPixels(src_line, srcOffset, this.frame_buffer_.stride(0), border_columns[border_columns_dst_index][0], 0, 2, 2, block_height4x4 * 4, 1);
         }

         this.PrepareCdefBlock(
            block_width4x4,
            block_height4x4,
            row4x4_start,
            column4x4_start,
            cdef_block,
            68,
            true,
            border_columns != null ? border_columns[border_columns_src_index] : null,
            use_border_columns[border_columns_src_index][0]
         );
         int[] direction_y = new int[64];
         int y_index = 0;
         int y_primary_strength = this.frame_header_.cdef.y_primary_strength[index];
         int y_secondary_strength = this.frame_header_.cdef.y_secondary_strength[index];
         int y_strength_index = y_secondary_strength == 0 ? 1 : 0;
         boolean compute_direction_and_variance = (y_primary_strength | this.frame_header_.cdef.uv_primary_strength[index]) != 0;
         int[] skip_row = this.cdef_skip_.data_;
         int skip_rowPos = this.cdef_skip_.columns() * (row4x4_start >> 1) + (column4x4_start >> 4);
         int skip_stride = this.cdef_skip_.columns();
         int row4x4 = row4x4_start;

         do {
            int[] cdef_buffer_base = cdef_buffer_row_base[0];
            int cdef_buffer_basePos = cdef_buffer_row_baseOffset[0];
            int[] src_buffer_base = src_buffer_row_base[0];
            int src_buffer_basePos = src_buffer_row_baseOffset[0];
            int[] cdef_src_base = cdef_src_row_base[0];
            int cdef_src_basePos = cdef_src_row_baseOffset[0];
            int column4x4 = column4x4_start;
            if (skip_row[skip_rowPos] == 0) {
               for (int i = 0; i < block_width4x4 / 2; y_index++) {
                  direction_y[y_index] = D.kCdefSkip;
                  i++;
               }

               if (this.thread_pool_ == null) {
                  CopyPixels(
                     src_buffer_base,
                     src_buffer_basePos,
                     this.frame_buffer_.stride(0),
                     cdef_buffer_base,
                     cdef_buffer_basePos,
                     this.frame_buffer_.stride(0),
                     64,
                     8,
                     1
                  );
               }
            } else {
               do {
                  int block_width = 8;
                  int block_height = 8;
                  int cdef_stride = this.frame_buffer_.stride(0);
                  int src_stride = this.frame_buffer_.stride(0);
                  int skip_shift = column4x4 >> 1 & 7;
                  boolean skip = (skip_row[skip_rowPos] >> skip_shift & 1) == 0;
                  if (skip) {
                     direction_y[y_index] = D.kCdefSkip;
                     if (this.thread_pool_ == null) {
                        CopyPixels(
                           src_buffer_base, src_buffer_basePos, src_stride, cdef_buffer_base, cdef_buffer_basePos, cdef_stride, block_width, block_height, 1
                        );
                     }
                  } else {
                     direction_y[y_index] = 0;
                     int[] variance = new int[]{0};
                     if (compute_direction_and_variance) {
                        if (this.thread_pool_ != null && row4x4 + 2 >= row4x4_start + block_height4x4) {
                           LogWriter.writeLog("Post Filter error: avif non null thread pool ");
                        } else {
                           Cdef.CdefDirection(src_buffer_base, src_buffer_basePos, src_stride, direction_y, y_index, variance);
                        }
                     }

                     int direction = y_primary_strength == 0 ? 0 : direction_y[y_index];
                     int variance_strength = variance[0] >> 6 != 0 ? Math.min(D.FloorLog2(variance[0] >> 6), 12) : 0;
                     int primary_strength = variance[0] != 0 ? y_primary_strength * (4 + variance_strength) + 8 >> 4 : 0;
                     if ((primary_strength | y_secondary_strength) == 0) {
                        if (this.thread_pool_ == null) {
                           CopyPixels(
                              src_buffer_base, src_buffer_basePos, src_stride, cdef_buffer_base, cdef_buffer_basePos, cdef_stride, block_width, block_height, 1
                           );
                        }
                     } else {
                        int strength_index = y_strength_index | (primary_strength == 0 ? 1 : 0) << 1;
                        int[] tempDirection = new int[]{direction};
                        Cdef.doCdef(
                           false,
                           1,
                           strength_index,
                           cdef_src_base,
                           cdef_src_basePos,
                           68,
                           cdef_buffer_base,
                           cdef_buffer_basePos,
                           cdef_stride,
                           primary_strength,
                           y_secondary_strength,
                           this.frame_header_.cdef.damping,
                           tempDirection,
                           0,
                           null,
                           block_height
                        );
                     }
                  }

                  cdef_buffer_basePos += column_step[0];
                  src_buffer_basePos += column_step[0];
                  cdef_src_basePos += column_step[0];
                  column4x4 += 2;
                  y_index++;
               } while (column4x4 < column4x4_start + block_width4x4);
            }

            cdef_buffer_row_baseOffset[0] += cdef_buffer_row_base_stride[0];
            src_buffer_row_baseOffset[0] += src_buffer_row_base_stride[0];
            cdef_src_row_baseOffset[0] += cdef_src_row_base_stride[0];
            skip_rowPos += skip_stride;
            row4x4 += 2;
         } while (row4x4 < row4x4_start + block_height4x4);

         if (this.planes_ != 1) {
            int uv_primary_strength = this.frame_header_.cdef.uv_primary_strength[index];
            int uv_secondary_strength = this.frame_header_.cdef.uv_secondary_strength[index];
            if ((uv_primary_strength | uv_secondary_strength) == 0) {
               if (this.thread_pool_ == null) {
                  for (int planex = 1; planex <= 2; planex++) {
                     CopyPixels(
                        src_buffer_row_base[planex],
                        src_buffer_row_baseOffset[planex],
                        this.frame_buffer_.stride(planex),
                        cdef_buffer_row_base[planex],
                        cdef_buffer_row_baseOffset[planex],
                        this.frame_buffer_.stride(planex),
                        block_width4x4 * 4 >> this.subsampling_x_[planex],
                        block_height4x4 * 4 >> this.subsampling_y_[planex],
                        1
                     );
                  }
               }

               use_border_columns[border_columns_dst_index][1] = false;
            } else {
               if (!is_frame_right && this.thread_pool_ != null) {
                  LogWriter.writeLog("Post Filter error: avif non null thread pool ");
               }

               this.PrepareCdefBlock(
                  block_width4x4,
                  block_height4x4,
                  row4x4_start,
                  column4x4_start,
                  cdef_block,
                  68,
                  false,
                  border_columns != null ? border_columns[border_columns_src_index] : null,
                  use_border_columns[border_columns_src_index][1]
               );
               int uv_strength_index = (uv_primary_strength == 0 ? 1 : 0) << 1 | (uv_secondary_strength == 0 ? 1 : 0);

               for (int var60 = 1; var60 <= 2; var60++) {
                  int subsampling_x = this.subsampling_x_[var60];
                  int subsampling_y = this.subsampling_y_[var60];
                  int block_width = 8 >> subsampling_x;
                  int block_height = 8 >> subsampling_y;
                  row4x4 = row4x4_start;
                  y_index = 0;

                  do {
                     int[] cdef_buffer_base = cdef_buffer_row_base[var60];
                     int cdef_buffer_basePos = cdef_buffer_row_baseOffset[var60];
                     int[] src_buffer_base = src_buffer_row_base[var60];
                     int src_buffer_basePos = src_buffer_row_baseOffset[var60];
                     int[] cdef_src_base = cdef_src_row_base[var60];
                     int cdef_src_basePos = cdef_src_row_baseOffset[var60];
                     int column4x4 = column4x4_start;

                     do {
                        int cdef_stride = this.frame_buffer_.stride(var60);
                        int src_stride = this.frame_buffer_.stride(var60);
                        boolean skip = (direction_y[y_index] & D.kCdefSkip) != 0;
                        int dual_cdef = 0;
                        if (skip) {
                           if (this.thread_pool_ == null) {
                              CopyPixels(
                                 src_buffer_base,
                                 src_buffer_basePos,
                                 src_stride,
                                 cdef_buffer_base,
                                 cdef_buffer_basePos,
                                 cdef_stride,
                                 block_width,
                                 block_height,
                                 1
                              );
                           }
                        } else {
                           if (column4x4 + 4 <= column4x4_start + block_width4x4) {
                              dual_cdef = subsampling_x;
                           }

                           int direction = uv_primary_strength == 0 ? 0 : D.kCdefUvDirection[subsampling_x][subsampling_y][direction_y[y_index]];
                           if (dual_cdef != 0) {
                              if (uv_primary_strength != 0 && direction_y[y_index] != direction_y[y_index + 1]) {
                                 dual_cdef = 0;
                              }

                              if (direction_y[y_index + 1] == D.kCdefSkip) {
                                 dual_cdef = 0;
                              }
                           }

                           int width_index = dual_cdef | subsampling_x ^ 1;
                           int[] tempDirection = new int[]{direction};
                           Cdef.doCdef(
                              false,
                              width_index,
                              uv_strength_index,
                              cdef_src_base,
                              cdef_src_basePos,
                              68,
                              cdef_buffer_base,
                              cdef_buffer_basePos,
                              cdef_stride,
                              uv_primary_strength,
                              uv_secondary_strength,
                              this.frame_header_.cdef.damping - 1,
                              tempDirection,
                              0,
                              null,
                              block_height
                           );
                        }

                        cdef_buffer_basePos += column_step[var60] << dual_cdef;
                        src_buffer_basePos += column_step[var60] << dual_cdef;
                        cdef_src_basePos += column_step[var60] << dual_cdef;
                        column4x4 += 2 << dual_cdef;
                        y_index += 1 << dual_cdef;
                     } while (column4x4 < column4x4_start + block_width4x4);

                     cdef_buffer_row_baseOffset[var60] += cdef_buffer_row_base_stride[var60];
                     src_buffer_row_baseOffset[var60] += src_buffer_row_base_stride[var60];
                     cdef_src_row_baseOffset[var60] += cdef_src_row_base_stride[var60];
                     row4x4 += 2;
                  } while (row4x4 < row4x4_start + block_height4x4);
               }
            }
         }
      }
   }

   void ApplyCdefForOneSuperBlockRowHelper(int[] cdef_block, int[][][] border_columns, int row4x4, int block_height4x4) {
      boolean[][] use_border_columns = new boolean[2][2];
      boolean non_zero_index = this.frame_header_.cdef.bits > 0;
      int[][] tempCDEF = D.fromView2D(this.cdef_index_);
      int[] cdef_index = non_zero_index ? tempCDEF[row4x4 / 16] : null;
      int cdef_indexPos = 0;
      int column4x4 = 0;

      do {
         int index = non_zero_index ? cdef_index[cdef_indexPos++] : 0;
         int block_width4x4 = Math.min(16, this.frame_header_.columns4x4 - column4x4);
         this.ApplyCdefForOneUnit(cdef_block, index, block_width4x4, block_height4x4, row4x4, column4x4, border_columns, use_border_columns);
         column4x4 += 16;
      } while (column4x4 < this.frame_header_.columns4x4);

      D.Array2DView tempView = D.toView2D(tempCDEF);
      int maxCopy = Math.min(tempView.data_.length, this.cdef_index_.data_.length);
      System.arraycopy(tempView.data_, 0, this.cdef_index_.data_, 0, maxCopy);
   }

   void ApplyCdefForOneSuperBlockRow(int row4x4_start, int sb4x4, boolean is_last_row) {
      int row4x4 = row4x4_start;
      int row4x4_limit = row4x4_start + sb4x4;

      while (row4x4 < this.frame_header_.rows4x4) {
         if (row4x4 > 0 && (!is_last_row || row4x4 == row4x4_start)) {
            this.ApplyCdefForOneSuperBlockRowHelper(this.cdef_block_, null, row4x4 - 2, 2);
         }

         int block_height4x4 = Math.min(16, this.frame_header_.rows4x4 - row4x4);
         int height4x4 = block_height4x4 - (is_last_row ? 0 : 2);
         if (height4x4 > 0) {
            this.ApplyCdefForOneSuperBlockRowHelper(this.cdef_block_, null, row4x4, height4x4);
         }

         row4x4 += 16;
         if (row4x4 >= row4x4_limit) {
            return;
         }
      }
   }

   void ApplyCdefWorker(int[] row4x4_atomic) {
      LogWriter.writeLog("apply cdef worker called");
      int[] cdef_block = new int[9248];
      int[][][] border_columns = new int[2][3][256];
   }

   private static int HevThresh(int level) {
      return level / 16;
   }

   static int GetLoopFilterSizeY(int filter_length) {
      return (filter_length > 4 ? 1 : 0) * 2 + (filter_length > 8 ? 1 : 0);
   }

   static int GetLoopFilterSizeUV(int filter_length) {
      return filter_length != 4 ? 1 : 0;
   }

   static boolean NonBlockBorderNeedsFilter(D.BlockParams bp, int filter_id, int[] level) {
      if (bp.deblock_filter_level[filter_id] != 0 && (!bp.skip || !bp.is_inter)) {
         level[0] = bp.deblock_filter_level[filter_id];
         return true;
      } else {
         return false;
      }
   }

   static void ComputeDeblockFilterLevelsHelper(D.ObuFrameHeader frame_header, int segment_id, int level_index, int[] delta_lf, int[][] deblock_filter_levels) {
      int delta = delta_lf[frame_header.delta_lf.multi ? level_index : 0];
      int level = D.Clip3(frame_header.loop_filter.level[level_index] + delta, 0, 63);
      int feature = 1 + level_index;
      level = D.Clip3(level + frame_header.segmentation.feature_data[segment_id][feature], 0, 63);
      if (!frame_header.loop_filter.delta_enabled) {
         for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 2; j++) {
               deblock_filter_levels[i][j] = level;
            }
         }
      } else {
         int shift = level >> 5;
         deblock_filter_levels[0][0] = D.Clip3(level + (frame_header.loop_filter.ref_deltas[0] << shift), 0, 63);

         for (int reference_frame = 1; reference_frame < 8; reference_frame++) {
            for (int mode_id = 0; mode_id < 2; mode_id++) {
               deblock_filter_levels[reference_frame][mode_id] = D.Clip3(
                  level + (frame_header.loop_filter.ref_deltas[reference_frame] + frame_header.loop_filter.mode_deltas[mode_id] << shift), 0, 63
               );
            }
         }
      }
   }

   void ComputeDeblockFilterLevels(int[] delta_lf, int[][][][] deblock_filter_levels) {
      if (this.DoDeblock()) {
         int num_segments = this.frame_header_.segmentation.enabled ? 8 : 1;

         for (int segment_id = 0; segment_id < num_segments; segment_id++) {
            int level_index;
            for (level_index = 0; level_index < 2; level_index++) {
               ComputeDeblockFilterLevelsHelper(this.frame_header_, segment_id, level_index, delta_lf, deblock_filter_levels[segment_id][level_index]);
            }

            for (; level_index < 4; level_index++) {
               if (this.frame_header_.loop_filter.level[level_index] != 0) {
                  ComputeDeblockFilterLevelsHelper(this.frame_header_, segment_id, level_index, delta_lf, deblock_filter_levels[segment_id][level_index]);
               }
            }
         }
      }
   }

   D.LoopRestorationInfo restoration_info() {
      return this.restoration_info_;
   }

   boolean GetHorizontalDeblockFilterEdgeInfo(int row4x4, int column4x4, int[] level, int[] step, int[] filter_length) {
      step[0] = D.kTransformHeight[this.inter_transform_sizes_[row4x4][column4x4]];
      if (row4x4 == 0) {
         return false;
      } else {
         D.BlockParams bp = this.block_parameters_.Find(row4x4, column4x4);
         int row4x4_prev = row4x4 - 1;
         D.BlockParams bp_prev = this.block_parameters_.Find(row4x4_prev, column4x4);
         if (bp == bp_prev) {
            if (!NonBlockBorderNeedsFilter(bp, 1, level)) {
               return false;
            }
         } else {
            int level_this = bp.deblock_filter_level[1];
            level[0] = level_this;
            if (level_this == 0) {
               int level_prev = bp_prev.deblock_filter_level[1];
               if (level_prev == 0) {
                  return false;
               }

               level[0] = level_prev;
            }
         }

         int step_prev = D.kTransformHeight[this.inter_transform_sizes_[row4x4_prev][column4x4]];
         filter_length[0] = Math.min(step[0], step_prev);
         return true;
      }
   }

   void GetHorizontalDeblockFilterEdgeInfoUV(int row4x4, int column4x4, int[] level_u, int[] level_v, int[] step, int[] filter_length) {
      int subsampling_x = this.subsampling_x_[1];
      int subsampling_y = this.subsampling_y_[1];
      row4x4 = D.GetDeblockPosition(row4x4, subsampling_y);
      column4x4 = D.GetDeblockPosition(column4x4, subsampling_x);
      D.BlockParams bp = this.block_parameters_.Find(row4x4, column4x4);
      level_u[0] = 0;
      level_v[0] = 0;
      step[0] = D.kTransformHeight[bp.uv_transform_size];
      if (row4x4 != subsampling_y) {
         boolean need_filter_u = this.frame_header_.loop_filter.level[2] != 0;
         boolean need_filter_v = this.frame_header_.loop_filter.level[3] != 0;
         int filter_id_u = D.kDeblockFilterLevelIndex[1][1];
         int filter_id_v = D.kDeblockFilterLevelIndex[2][1];
         int row4x4_prev = row4x4 - (1 << subsampling_y);
         D.BlockParams bp_prev = this.block_parameters_.Find(row4x4_prev, column4x4);
         if (bp != bp_prev) {
            if (need_filter_u) {
               int level_u_this = bp.deblock_filter_level[filter_id_u];
               level_u[0] = level_u_this;
               if (level_u_this == 0) {
                  level_u[0] = bp_prev.deblock_filter_level[filter_id_u];
               }
            }

            if (need_filter_v) {
               int level_v_this = bp.deblock_filter_level[filter_id_v];
               level_v[0] = level_v_this;
               if (level_v_this == 0) {
                  level_v[0] = bp_prev.deblock_filter_level[filter_id_v];
               }
            }

            int step_prev = D.kTransformHeight[bp_prev.uv_transform_size];
            filter_length[0] = Math.min(step[0], step_prev);
         } else {
            boolean skip = bp.skip && bp.is_inter;
            need_filter_u = need_filter_u && bp.deblock_filter_level[filter_id_u] != 0 && !skip;
            need_filter_v = need_filter_v && bp.deblock_filter_level[filter_id_v] != 0 && !skip;
            if (need_filter_u || need_filter_v) {
               if (need_filter_u) {
                  level_u[0] = bp.deblock_filter_level[filter_id_u];
               }

               if (need_filter_v) {
                  level_v[0] = bp.deblock_filter_level[filter_id_v];
               }

               filter_length[0] = step[0];
            }
         }
      }
   }

   boolean GetVerticalDeblockFilterEdgeInfo(int row4x4, int column4x4, D.BlockParams bp_ptr, int[] level, int[] step, int[] filter_length) {
      step[0] = D.kTransformWidth[this.inter_transform_sizes_[row4x4][column4x4]];
      if (column4x4 == 0) {
         return false;
      } else {
         int filter_id = 0;
         int column4x4_prev = column4x4 - 1;
         int bp_prevPos = bp_ptr.pos - 1;
         if (bp_prevPos == -1) {
            bp_prevPos = 0;
         }

         D.BlockParams bp_prev = this.block_parameters_.findByIndex(bp_prevPos);
         if (bp_ptr.pos == bp_prev.pos) {
            if (!NonBlockBorderNeedsFilter(bp_ptr, filter_id, level)) {
               return false;
            }
         } else {
            int level_this = bp_ptr.deblock_filter_level[filter_id];
            level[0] = level_this;
            if (level_this == 0) {
               int level_prev = bp_prev.deblock_filter_level[filter_id];
               if (level_prev == 0) {
                  return false;
               }

               level[0] = level_prev;
            }
         }

         int step_prev = D.kTransformWidth[this.inter_transform_sizes_[row4x4][column4x4_prev]];
         filter_length[0] = Math.min(step[0], step_prev);
         return true;
      }
   }

   void GetVerticalDeblockFilterEdgeInfoUV(int column4x4, D.BlockParams bp_ptr, int[] level_u, int[] level_v, int[] step, int[] filter_length) {
      int subsampling_x = this.subsampling_x_[1];
      column4x4 = D.GetDeblockPosition(column4x4, subsampling_x);
      level_u[0] = 0;
      level_v[0] = 0;
      step[0] = D.kTransformWidth[bp_ptr.uv_transform_size];
      if (column4x4 != subsampling_x) {
         boolean need_filter_u = this.frame_header_.loop_filter.level[2] != 0;
         boolean need_filter_v = this.frame_header_.loop_filter.level[3] != 0;
         int filter_id_u = D.kDeblockFilterLevelIndex[1][0];
         int filter_id_v = D.kDeblockFilterLevelIndex[2][0];
         int bp_prevPos = bp_ptr.pos - (1 << subsampling_x);
         D.BlockParams bp_prev = this.block_parameters_.findByIndex(bp_prevPos);
         if (bp_ptr.pos != bp_prev.pos) {
            if (need_filter_u) {
               int level_u_this = bp_ptr.deblock_filter_level[filter_id_u];
               level_u[0] = level_u_this;
               if (level_u_this == 0) {
                  level_u[0] = bp_prev.deblock_filter_level[filter_id_u];
               }
            }

            if (need_filter_v) {
               int level_v_this = bp_ptr.deblock_filter_level[filter_id_v];
               level_v[0] = level_v_this;
               if (level_v_this == 0) {
                  level_v[0] = bp_prev.deblock_filter_level[filter_id_v];
               }
            }

            int step_prev = D.kTransformWidth[bp_prev.uv_transform_size];
            filter_length[0] = Math.min(step[0], step_prev);
         } else {
            boolean skip = bp_ptr.skip && bp_ptr.is_inter;
            need_filter_u = need_filter_u && bp_ptr.deblock_filter_level[filter_id_u] != 0 && !skip;
            need_filter_v = need_filter_v && bp_ptr.deblock_filter_level[filter_id_v] != 0 && !skip;
            if (need_filter_u || need_filter_v) {
               if (need_filter_u) {
                  level_u[0] = bp_ptr.deblock_filter_level[filter_id_u];
               }

               if (need_filter_v) {
                  level_v[0] = bp_ptr.deblock_filter_level[filter_id_v];
               }

               filter_length[0] = step[0];
            }
         }
      }
   }

   void HorizontalDeblockFilter(int row4x4_start, int row4x4_end, int column4x4_start, int column4x4_end) {
      int height4x4 = row4x4_end - row4x4_start;
      int width4x4 = column4x4_end - column4x4_start;
      if (height4x4 > 0 && width4x4 > 0) {
         int column_step = 1;
         int src_step = 4 << this.pixel_size_log2_;
         int src_stride = this.frame_buffer_.stride(0);
         int[] src = this.source_buffer_[0];
         int srcPos = this.source_buffer_pos[0] + this.GetBufferOffset(src_stride, 0, row4x4_start, column4x4_start);
         int[] row_step = new int[]{0};
         int[] level = new int[]{0};
         int[] filter_length = new int[]{0};
         int width = this.frame_header_.width;
         int height = this.frame_header_.height;

         for (int column4x4 = 0; column4x4 < width4x4 && (column4x4_start + column4x4) * 4 < width; srcPos += src_step) {
            int[] src_row = src;
            int src_rowPos = srcPos;

            for (int row4x4 = 0; row4x4 < height4x4 && (row4x4_start + row4x4) * 4 < height; row4x4 += row_step[0]) {
               boolean need_filter = this.GetHorizontalDeblockFilterEdgeInfo(row4x4_start + row4x4, column4x4_start + column4x4, level, row_step, filter_length);
               if (need_filter) {
                  int size = GetLoopFilterSizeY(filter_length[0]);
                  Loop.doFilter(size, 1, src_row, src_rowPos, src_stride, this.outer_thresh_[level[0]], this.inner_thresh_[level[0]], HevThresh(level[0]));
               }

               src_rowPos += row_step[0] * src_stride;
               row_step[0] /= 4;
            }

            column4x4 += column_step;
         }

         if (this.needs_chroma_deblock_) {
            int subsampling_x = this.subsampling_x_[1];
            int subsampling_y = this.subsampling_y_[1];
            column_step = 1 << subsampling_x;
            int src_stride_u = this.frame_buffer_.stride(1);
            int src_stride_v = this.frame_buffer_.stride(2);
            int[] src_u = this.source_buffer_[1];
            int src_uPos = this.source_buffer_pos[1] + this.GetBufferOffset(src_stride_u, 1, row4x4_start, column4x4_start);
            int[] src_v = this.source_buffer_[2];
            int src_vPos = this.source_buffer_pos[2] + this.GetBufferOffset(src_stride_v, 1, row4x4_start, column4x4_start);
            row_step[0] = 0;
            int[] level_u = new int[]{0};
            int[] level_v = new int[]{0};
            filter_length[0] = 0;

            for (int column4x4 = 0; column4x4 < width4x4 && (column4x4_start + column4x4) * 4 < width; src_vPos += src_step) {
               int[] src_row_u = src_u;
               int src_row_uPos = src_uPos;
               int[] src_row_v = src_v;
               int src_row_vPos = src_vPos;

               for (int row4x4 = 0; row4x4 < height4x4 && (row4x4_start + row4x4) * 4 < height; row4x4 += row_step[0]) {
                  this.GetHorizontalDeblockFilterEdgeInfoUV(row4x4_start + row4x4, column4x4_start + column4x4, level_u, level_v, row_step, filter_length);
                  if (level_u[0] != 0) {
                     int size = GetLoopFilterSizeUV(filter_length[0]);
                     Loop.doFilter(
                        size, 1, src_row_u, src_row_uPos, src_stride_u, this.outer_thresh_[level_u[0]], this.inner_thresh_[level_u[0]], HevThresh(level_u[0])
                     );
                  }

                  if (level_v[0] != 0) {
                     int size = GetLoopFilterSizeUV(filter_length[0]);
                     Loop.doFilter(
                        size, 1, src_row_v, src_row_vPos, src_stride_v, this.outer_thresh_[level_v[0]], this.inner_thresh_[level_v[0]], HevThresh(level_v[0])
                     );
                  }

                  src_row_uPos += row_step[0] * src_stride_u;
                  src_row_vPos += row_step[0] * src_stride_v;
                  row_step[0] = (row_step[0] << subsampling_y) / 4;
               }

               column4x4 += column_step;
               src_uPos += src_step;
            }
         }
      }
   }

   void VerticalDeblockFilter(int row4x4_start, int row4x4_end, int column4x4_start, int column4x4_end) {
      int height4x4 = row4x4_end - row4x4_start;
      int width4x4 = column4x4_end - column4x4_start;
      if (height4x4 > 0 && width4x4 > 0) {
         int row_stride = this.frame_buffer_.stride(0) * 4;
         int src_stride = this.frame_buffer_.stride(0);
         int[] src = this.source_buffer_[0];
         int srcPos = this.source_buffer_pos[0] + this.GetBufferOffset(src_stride, 0, row4x4_start, column4x4_start);
         int[] column_step = new int[]{0};
         int[] level = new int[]{0};
         int[] filter_length = new int[]{0};
         D.BlockParams bp_row_base = this.block_parameters_.Find(row4x4_start, column4x4_start);
         int bp_row_basePos = row4x4_start * this.block_parameters_.columns4x4() + column4x4_start;
         int bp_stride = this.block_parameters_.columns4x4();
         int column_step_shift = this.pixel_size_log2_;
         int width = this.frame_header_.width;
         int height = this.frame_header_.height;

         for (int row4x4 = 0; row4x4 < height4x4 && (row4x4_start + row4x4) * 4 < height; bp_row_basePos += bp_stride) {
            int[] src_row = src;
            int src_rowPos = srcPos;
            D.BlockParams bp = this.block_parameters_.block_parameters_cache_[bp_row_basePos];
            int bpPos = bp_row_basePos;

            for (int column4x4 = 0; column4x4 < width4x4 && (column4x4_start + column4x4) * 4 < width; bpPos += column_step[0]) {
               boolean need_filter = this.GetVerticalDeblockFilterEdgeInfo(
                  row4x4_start + row4x4, column4x4_start + column4x4, bp, level, column_step, filter_length
               );
               if (need_filter) {
                  int size = GetLoopFilterSizeY(filter_length[0]);
                  Loop.doFilter(size, 0, src_row, src_rowPos, src_stride, this.outer_thresh_[level[0]], this.inner_thresh_[level[0]], HevThresh(level[0]));
               }

               src_rowPos += column_step[0] << column_step_shift;
               column_step[0] /= 4;
               column4x4 += column_step[0];
            }

            row4x4++;
            srcPos += row_stride;
         }

         if (this.needs_chroma_deblock_) {
            int subsampling_x = this.subsampling_x_[1];
            int subsampling_y = this.subsampling_y_[1];
            int src_stride_u = this.frame_buffer_.stride(1);
            int src_stride_v = this.frame_buffer_.stride(2);
            int row_step = 1 << subsampling_y;
            int[] src_u = this.source_buffer_[1];
            int src_uPos = this.source_buffer_pos[1] + this.GetBufferOffset(src_stride_u, 1, row4x4_start, column4x4_start);
            int[] src_v = this.source_buffer_[2];
            int src_vPos = this.source_buffer_pos[2] + this.GetBufferOffset(src_stride_v, 1, row4x4_start, column4x4_start);
            int row_stride_u = this.frame_buffer_.stride(1) * 4;
            int row_stride_v = this.frame_buffer_.stride(2) * 4;
            int type = 0;
            column_step[0] = 0;
            int[] level_u = new int[]{0};
            int[] level_v = new int[]{0};
            filter_length[0] = 0;
            bp_row_base = this.block_parameters_.Find(D.GetDeblockPosition(row4x4_start, subsampling_y), D.GetDeblockPosition(column4x4_start, subsampling_x));
            bp_stride = this.block_parameters_.columns4x4() << subsampling_y;

            for (int row4x4 = 0; row4x4 < height4x4 && (row4x4_start + row4x4) * 4 < height; bp_row_basePos += bp_stride) {
               int[] src_row_u = src_u;
               int src_row_uPos = src_uPos;
               int[] src_row_v = src_v;
               int src_row_vPos = src_vPos;
               D.BlockParams bp = this.block_parameters_.findByIndex(bp_row_basePos);
               int bpPos = bp_row_basePos;

               for (int column4x4 = 0; column4x4 < width4x4 && (column4x4_start + column4x4) * 4 < width; bpPos += column_step[0]) {
                  bp = this.block_parameters_.findByIndex(bpPos);
                  this.GetVerticalDeblockFilterEdgeInfoUV(column4x4_start + column4x4, bp, level_u, level_v, column_step, filter_length);
                  if (level_u[0] != 0) {
                     int size = GetLoopFilterSizeUV(filter_length[0]);
                     Loop.doFilter(
                        size,
                        type,
                        src_row_u,
                        src_row_uPos,
                        src_stride_u,
                        this.outer_thresh_[level_u[0]],
                        this.inner_thresh_[level_u[0]],
                        HevThresh(level_u[0])
                     );
                  }

                  if (level_v[0] != 0) {
                     int size = GetLoopFilterSizeUV(filter_length[0]);
                     Loop.doFilter(
                        size,
                        type,
                        src_row_v,
                        src_row_vPos,
                        src_stride_v,
                        this.outer_thresh_[level_v[0]],
                        this.inner_thresh_[level_v[0]],
                        HevThresh(level_v[0])
                     );
                  }

                  src_row_uPos += column_step[0] << column_step_shift;
                  src_row_vPos += column_step[0] << column_step_shift;
                  column_step[0] = (column_step[0] << subsampling_x) / 4;
                  column4x4 += column_step[0];
               }

               row4x4 += row_step;
               src_uPos += row_stride_u;
               src_vPos += row_stride_v;
            }
         }
      }
   }

   void ApplyDeblockFilter(int loop_filter_type, int row4x4_start, int column4x4_start, int column4x4_end, int sb4x4) {
      column4x4_end = Math.min(Align(column4x4_end, 16), this.frame_header_.columns4x4);
      if (column4x4_start < column4x4_end) {
         LogWriter.writeLog("apply deblock filter not supported");
      }
   }

   int ApplyFilteringForOneSuperBlockRow(int row4x4, int sb4x4, boolean is_last_row, boolean do_deblock) {
      if (row4x4 < 0) {
         return -1;
      } else {
         if (this.DoDeblock() && do_deblock) {
            this.VerticalDeblockFilter(row4x4, row4x4 + sb4x4, 0, this.frame_header_.columns4x4);
            this.HorizontalDeblockFilter(row4x4, row4x4 + sb4x4, 0, this.frame_header_.columns4x4);
         }

         if (this.DoRestoration() && this.DoCdef()) {
            this.SetupLoopRestorationBorder(row4x4, sb4x4);
         }

         if (this.DoCdef()) {
         }

         if (this.DoSuperRes()) {
            LogWriter.writeLog("PostFilter : super resolution not supported");
         }

         if (this.DoRestoration()) {
            this.CopyBordersForOneSuperBlockRow(row4x4, sb4x4, true);
            this.ApplyLoopRestoration(row4x4, sb4x4);
            if (is_last_row) {
               this.CopyBordersForOneSuperBlockRow(row4x4 + sb4x4, 16, true);
               this.ApplyLoopRestoration(row4x4 + sb4x4, 16);
            }
         }

         if (this.frame_header_.refresh_frame_flags != 0 && this.DoBorderExtensionInLoop()) {
            this.CopyBordersForOneSuperBlockRow(row4x4, sb4x4, false);
            if (is_last_row) {
               this.CopyBordersForOneSuperBlockRow(row4x4 + sb4x4, 16, false);
            }
         }

         if (is_last_row && !this.DoBorderExtensionInLoop()) {
            this.ExtendBordersForReferenceFrame();
         }

         return is_last_row ? this.frame_header_.height : this.progress_row_;
      }
   }

   void ApplyLoopRestoration(int row4x4_start, int sb4x4) {
      this.ApplyLoopRestorationForOneSuperBlockRow(row4x4_start, sb4x4);
   }

   void ApplyLoopRestorationForOneSuperBlockRow(int row4x4_start, int sb4x4) {
      int plane = 0;
      int upscaled_width = this.frame_header_.upscaled_width;
      int height = this.frame_header_.height;

      do {
         if (this.loop_restoration_.type[plane] != 0) {
            int stride = this.frame_buffer_.stride(plane);
            int unit_height_offset = 8 >> this.subsampling_y_[plane];
            int plane_height = SubsampledValue(height, this.subsampling_y_[plane]);
            int plane_width = SubsampledValue(upscaled_width, this.subsampling_x_[plane]);
            int plane_unit_size = 1 << this.loop_restoration_.unit_size_log2[plane];
            int plane_process_unit_height = 64 >> this.subsampling_y_[plane];
            int y = row4x4_start == 0 ? 0 : (row4x4_start * 4 >> this.subsampling_y_[plane]) - unit_height_offset;
            int expected_height = plane_process_unit_height - (row4x4_start == 0 ? unit_height_offset : 0);
            int sb_y = 0;

            while (sb_y < sb4x4 && y < plane_height) {
               int unit_row = Math.min(
                  y + unit_height_offset >> this.loop_restoration_.unit_size_log2[plane], this.restoration_info_.num_vertical_units(plane) - 1
               );
               int current_process_unit_height = Math.min(expected_height, plane_height - y);
               expected_height = plane_process_unit_height;
               int super_resPos = this.superres_buffer_pos[plane];
               this.ApplyLoopRestorationForOneRow(
                  this.superres_buffer_[plane],
                  super_resPos,
                  stride,
                  plane,
                  plane_height,
                  plane_width,
                  y,
                  unit_row,
                  current_process_unit_height,
                  plane_unit_size,
                  this.loop_restoration_buffer_[plane],
                  y * stride
               );
               sb_y += 16;
               y += current_process_unit_height;
            }
         }
      } while (++plane < this.planes_);
   }

   private void ApplyLoopRestorationForOneRow(
      int[] src_buffer,
      int src_bufferPos,
      int stride,
      int plane,
      int plane_height,
      int plane_width,
      int unit_y,
      int unit_row,
      int current_process_unit_height,
      int plane_unit_size,
      int[] dst_buffer,
      int dst_bufferPos
   ) {
      int num_horizontal_units = this.restoration_info_.num_horizontal_units(plane);
      D.RestorationUnitInfo[] restoration_info = this.restoration_info_.loop_restoration_info_[plane];
      int restoation_infoPos = unit_row * num_horizontal_units;
      boolean in_place = this.DoCdef() || this.thread_pool_ != null;
      int[] border = null;
      int border_stride = 0;
      int borderPos = 0;
      src_bufferPos += unit_y * stride;
      if (in_place) {
         int border_unit_y = Math.max(D.RightShiftWithCeiling(unit_y, 4 - this.subsampling_y_[plane]) - 4, 0);
         border_stride = this.loop_restoration_border_.stride(plane);
         border = this.loop_restoration_border_.data(plane);
         borderPos = border_unit_y * border_stride;
      }

      int unit_column = 0;
      int column = 0;

      do {
         int current_process_unit_width = Math.min(plane_unit_size, plane_width - column);
         int[] src = src_buffer;
         int srcPos = src_bufferPos + column;
         unit_column = Math.min(unit_column, num_horizontal_units - 1);
         if (restoration_info[restoation_infoPos + unit_column].type == 0) {
            int[] dst = dst_buffer;
            int dstPos = dst_bufferPos + column;
            if (in_place) {
               int k = current_process_unit_height;

               do {
                  Mem.move(dst, dstPos, src, srcPos, current_process_unit_width);
                  srcPos += stride;
                  dstPos += stride;
               } while (--k != 0);
            } else {
               LogWriter.writeLog("copy plane not supported yet");
            }
         } else {
            int[] top_border = src_buffer;
            int top_borderPos = srcPos - 2 * stride;
            int top_border_stride = stride;
            int[] bottom_border = src_buffer;
            int bottom_boderPos = srcPos + current_process_unit_height * stride;
            int bottom_border_stride = stride;
            boolean frame_bottom_border = unit_y + current_process_unit_height >= plane_height;
            if (in_place && (unit_y != 0 || !frame_bottom_border)) {
               int loop_restoration_borderPos = borderPos + column;
               if (unit_y != 0) {
                  top_border = border;
                  top_borderPos = loop_restoration_borderPos;
                  top_border_stride = border_stride;
                  loop_restoration_borderPos += 4 * border_stride;
               }

               if (!frame_bottom_border) {
                  bottom_border = border;
                  bottom_boderPos = loop_restoration_borderPos + 2 * border_stride;
                  bottom_border_stride = border_stride;
               }
            }

            D.RestorationBuffer restoration_buffer = new D.RestorationBuffer();
            int type = restoration_info[unit_column].type;
            Loop.doFilterRestoration(
               type - 2,
               restoration_info[unit_column],
               src_buffer,
               srcPos,
               stride,
               top_border,
               top_borderPos,
               top_border_stride,
               bottom_border,
               bottom_boderPos,
               bottom_border_stride,
               current_process_unit_width,
               current_process_unit_height,
               restoration_buffer,
               dst_buffer,
               dst_bufferPos + column
            );
         }

         unit_column++;
         column += plane_unit_size;
      } while (column < plane_width);
   }

   boolean DoBorderExtensionInLoop() {
      return !this.frame_header_.allow_intrabc || this.frame_header_.upscaled_width == this.frame_header_.columns4x4 * 4;
   }
}
