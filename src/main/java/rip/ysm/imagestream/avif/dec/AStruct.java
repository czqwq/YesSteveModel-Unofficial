package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.utility.WriterByteLittle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import rip.ysm.imagestream.internal.LogWriter;

public class AStruct {
   D.FrameScratchBufferPool frame_scratch_buffer_pool_ = new D.FrameScratchBufferPool();
   D.DecoderSettings settings_ = new D.DecoderSettings();
   boolean is_frame_parallel_ = false;
   D.ObuSequenceHeader sequence_header_ = new D.ObuSequenceHeader();
   final int[][][][] quantizer_matrix_ = new int[15][2][19][];
   int[][][] wedge_masks_;
   D.BufferPool buffer_pool_ = new D.BufferPool();
   D.DecoderState state_ = new D.DecoderState();
   boolean has_sequence_header_;

   public static BufferedImage getDecodedImage(byte[] data) {
      AStruct struct = new AStruct();
      int operatingPoint = 0;
      Obu obu = new Obu(data, data.length, operatingPoint, struct.buffer_pool_, struct.state_);
      if (struct.has_sequence_header_) {
         obu.sequence_header_ = struct.sequence_header_;
      }

      int status = obu.parseOneFrame();
      D.RefCountedBuffer current_frame = obu.current_frame_;
      Quant.initializeQuantizerMatrix(struct.quantizer_matrix_);
      int imageWidth = obu.sequence_header_.max_frame_width;
      int imageHeight = obu.sequence_header_.max_frame_height;
      D.EncodedFrame encodedFrame = new D.EncodedFrame(obu, struct.state_, current_frame, 0);
      BufferedImage image = new BufferedImage(imageWidth, imageHeight, 1);
      struct.DecodeFrame(encodedFrame, image);
      return image;
   }

   private static void writeIVFData(int imageWidth, int imageHeight, byte[] data) throws IOException {
      byte[] header = new byte[32];
      WriterByteLittle wb = new WriterByteLittle(header);
      wb.write("DKIF".getBytes());
      wb.putU16(0);
      wb.putU16(32);
      wb.write("AV01".getBytes());
      wb.putU16(imageWidth);
      wb.putU16(imageHeight);
      wb.putU32(1);
      wb.putU32(1);
      wb.putU32(data.length);
      wb.putU32(0);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      bos.write(header);
      byte[] temporal = new byte[12];
      wb = new WriterByteLittle(temporal);
      wb.putU32(data.length);
      wb.putU32(1);
      wb.putU32(1);
      bos.write(temporal);
      bos.write(data);
   }

   static int GetBottomBorderPixels(boolean do_cdef, boolean do_restoration, boolean do_superres, int subsampling_y) {
      int extra_border = 0;
      if (do_cdef) {
         extra_border += 2;
      } else if (do_restoration) {
         extra_border += 2;
      }

      if (do_superres) {
         extra_border++;
      }

      extra_border <<= subsampling_y;
      return D.Align(64 + extra_border, 2);
   }

   static void SetSegmentationMap(D.ObuFrameHeader frame_header, D.SegmentationMap prev_segment_ids, D.RefCountedBuffer frame) {
      if (!frame_header.segmentation.enabled) {
         frame.segmentation_map().Clear();
      } else if (!frame_header.segmentation.update_map) {
         if (prev_segment_ids == null) {
            frame.segmentation_map().Clear();
         } else {
            frame.segmentation_map().CopyFrom(prev_segment_ids);
         }
      }
   }

   private int DecodeTilesNonFrameParallel(
      D.ObuSequenceHeader sequence_header, D.ObuFrameHeader frame_header, List<Tile> tiles, D.FrameScratchBuffer frame_scratch_buffer, PostFilter post_filter
   ) {
      int block_width4x4 = sequence_header.use_128x128_superblock ? 32 : 16;
      D.TileScratchBuffer tile_scratch_buffer = frame_scratch_buffer.tile_scratch_buffer_pool.get();

      for (int row4x4 = 0; row4x4 < frame_header.rows4x4; row4x4 += block_width4x4) {
         for (Tile tile_ptr : tiles) {
            tile_ptr.processSuperBlockRow(row4x4, tile_scratch_buffer, 2, true);
         }

         post_filter.ApplyFilteringForOneSuperBlockRow(row4x4, block_width4x4, row4x4 + block_width4x4 >= frame_header.rows4x4, true);
      }

      return 0;
   }

   private int DecodeTilesFrameParallel(
      D.ObuSequenceHeader sequence_header,
      D.ObuFrameHeader frame_header,
      List<Tile> tiles,
      Symbol saved_symbol_decoder_context,
      D.SegmentationMap prev_segment_ids,
      D.FrameScratchBuffer frame_scratch_buffer,
      PostFilter post_filter,
      D.RefCountedBuffer current_frame
   ) {
      for (Tile tile : tiles) {
         if (!tile.parse()) {
            return -1;
         }
      }

      if (frame_header.enable_frame_end_update_cdf) {
         frame_scratch_buffer.symbol_decoder_context = saved_symbol_decoder_context;
      }

      current_frame.SetFrameContext(frame_scratch_buffer.symbol_decoder_context);
      SetSegmentationMap(frame_header, prev_segment_ids, current_frame);
      current_frame.SetFrameState(2);
      D.TileScratchBuffer tile_scratch_buffer = frame_scratch_buffer.tile_scratch_buffer_pool.get();
      if (tile_scratch_buffer == null) {
         return -3;
      } else {
         int block_width4x4 = sequence_header.use_128x128_superblock ? 32 : 16;

         for (int row4x4 = 0; row4x4 < frame_header.rows4x4; row4x4 += block_width4x4) {
            for (Tile tile_ptr : tiles) {
               if (!tile_ptr.processSuperBlockRow(row4x4, tile_scratch_buffer, 1, false)) {
                  return -1;
               }
            }

            int progress_row = post_filter.ApplyFilteringForOneSuperBlockRow(row4x4, block_width4x4, row4x4 + block_width4x4 >= frame_header.rows4x4, true);
            if (progress_row >= 0) {
               current_frame.SetProgress(progress_row);
            }
         }

         current_frame.SetFrameState(3);
         return 0;
      }
   }

   private void ApplyDeblockingFilterForTileBoundaries(
      PostFilter post_filter,
      List<Tile> tile_row_base,
      D.ObuFrameHeader frame_header,
      int row4x4,
      int block_width4x4,
      int tile_columns,
      boolean decode_entire_tiles_in_worker_threads
   ) {
      for (int tile_column = 0; tile_column < tile_columns; tile_column++) {
         Tile tile = tile_row_base.get(tile_column);
         post_filter.ApplyDeblockFilter(0, row4x4, tile.column4x4_start(), tile.column4x4_start() + 16, block_width4x4);
      }

      if (decode_entire_tiles_in_worker_threads && row4x4 == tile_row_base.get(0).row4x4_start()) {
         post_filter.ApplyDeblockFilter(1, row4x4, 0, frame_header.columns4x4, block_width4x4);
      } else {
         Tile first_tile = tile_row_base.get(0);
         post_filter.ApplyDeblockFilter(1, row4x4, first_tile.column4x4_start(), first_tile.column4x4_start() + 16, block_width4x4);

         for (int tile_column = 1; tile_column < tile_columns; tile_column++) {
            Tile tile = tile_row_base.get(tile_column);
            Tile previous_tile = tile_row_base.get(tile_column - 1);
            int column4x4_start = tile.column4x4_start() - (tile.column4x4_start() - 16 != previous_tile.column4x4_start() ? 16 : 0);
            post_filter.ApplyDeblockFilter(1, row4x4, column4x4_start, tile.column4x4_start() + 16, block_width4x4);
         }

         Tile last_tile = tile_row_base.get(tile_columns - 1);
         int column4x4_start = last_tile.column4x4_end() - 1 & -16;
         if (column4x4_start != last_tile.column4x4_start()) {
            post_filter.ApplyDeblockFilter(1, row4x4, column4x4_start, last_tile.column4x4_end(), block_width4x4);
         }
      }
   }

   private void DecodeSuperBlockRowInTile(
      List<Tile> tiles,
      int tile_index,
      int row4x4,
      int superblock_size4x4,
      int tile_columns,
      int superblock_rows,
      D.FrameScratchBuffer frame_scratch_buffer,
      PostFilter post_filter
   ) {
      D.TileScratchBuffer scratch_buffer = frame_scratch_buffer.tile_scratch_buffer_pool.get();
      Tile tile = tiles.get(tile_index);
      boolean ok = tile.processSuperBlockRow(row4x4, scratch_buffer, 1, false);
      if (post_filter.DoDeblock()) {
         post_filter.ApplyDeblockFilter(0, row4x4, tile.column4x4_start() + 16, tile.column4x4_end(), superblock_size4x4);
         post_filter.ApplyDeblockFilter(1, row4x4, tile.column4x4_start() + 16, tile.column4x4_end() - 16, superblock_size4x4);
      }

      int superblock_size4x4_log2 = D.FloorLog2(superblock_size4x4);
      int index = row4x4 >> superblock_size4x4_log2;
      int next_row4x4 = row4x4 + superblock_size4x4;
      if (!tile.IsRow4x4Inside(next_row4x4)) {
         tile_index += tile_columns;
      }

      if (tile_index < tiles.size()) {
         this.DecodeSuperBlockRowInTile(tiles, tile_index, next_row4x4, superblock_size4x4, tile_columns, superblock_rows, frame_scratch_buffer, post_filter);
      }
   }

   private int DecodeFrame(D.EncodedFrame encoded_frame, BufferedImage image) {
      D.ObuSequenceHeader sequence_header = encoded_frame.sequence_header;
      D.ObuFrameHeader frame_header = encoded_frame.frame_header;
      D.RefCountedBuffer current_frame = encoded_frame.frame;
      D.FrameScratchBuffer frame_scratch_buffer = this.frame_scratch_buffer_pool_.get();
      if (!frame_header.show_existing_frame) {
         if (encoded_frame.tile_buffers.isEmpty()) {
            return 0;
         }

         int status = this.DecodeTiles(sequence_header, frame_header, encoded_frame.tile_buffers, encoded_frame.state, frame_scratch_buffer, current_frame);
         if (status != 0) {
            return status;
         }
      } else if (!current_frame.WaitUntilDecoded()) {
         return -1;
      }

      if (!frame_header.show_frame && !frame_header.show_existing_frame) {
         return 0;
      } else if (!sequence_header.color_config.is_monochrome) {
         Yuv.doFastBT601(image, sequence_header, current_frame);
         return 0;
      } else {
         int[] dataY = current_frame.yuv_buffer_.data(0);
         int stride0 = current_frame.yuv_buffer_.stride(0);

         for (int y = 0; y < image.getHeight(); y++) {
            int indexd = y * stride0;

            for (int x = 0; x < image.getWidth(); x++) {
               int v = dataY[indexd++];
               image.setRGB(x, y, v << 16 | v << 8 | v);
            }
         }

         return 0;
      }
   }

   private int DecodeTiles(
      D.ObuSequenceHeader sequence_header,
      D.ObuFrameHeader frame_header,
      List<D.TileBuffer> tile_buffers,
      D.DecoderState state,
      D.FrameScratchBuffer frame_scratch_buffer,
      D.RefCountedBuffer current_frame
   ) {
      if (!frame_scratch_buffer.loop_restoration_info
         .reset(
            frame_header.loop_restoration,
            frame_header.upscaled_width,
            frame_header.height,
            sequence_header.color_config.subsampling_x,
            sequence_header.color_config.subsampling_y,
            sequence_header.color_config.is_monochrome
         )) {
         return -3;
      } else {
         D.ThreadingStrategy threading_strategy = frame_scratch_buffer.threading_strategy;
         if (!this.is_frame_parallel_ && !threading_strategy.Reset(frame_header, this.settings_.threads)) {
            return -3;
         } else {
            boolean do_cdef = PostFilter.DoCdef(frame_header, this.settings_.post_filter_mask);
            int num_planes = sequence_header.color_config.is_monochrome ? 1 : 3;
            boolean do_restoration = PostFilter.DoRestoration(frame_header.loop_restoration, this.settings_.post_filter_mask, num_planes);
            boolean do_superres = PostFilter.DoSuperRes(frame_header, this.settings_.post_filter_mask);
            int bottom_border = GetBottomBorderPixels(
               do_cdef && threading_strategy.post_filter_thread_pool() == null, do_restoration, do_superres, sequence_header.color_config.subsampling_y
            );
            current_frame.set_chroma_sample_position(sequence_header.color_config.chroma_sample_position);
            current_frame.realloc(
               sequence_header.color_config.bitdepth,
               sequence_header.color_config.is_monochrome,
               frame_header.upscaled_width,
               frame_header.height,
               sequence_header.color_config.subsampling_x,
               sequence_header.color_config.subsampling_y,
               64,
               64,
               64,
               bottom_border
            );
            if (frame_header.cdef.bits > 0) {
               frame_scratch_buffer.cdef_index.Reset((frame_header.rows4x4 + 32) / 16, (frame_header.columns4x4 + 32) / 16);
            }

            if (do_cdef) {
               frame_scratch_buffer.cdef_skip.Reset((frame_header.rows4x4 + 32) / 2, (frame_header.columns4x4 + 32) / 16);
            }

            frame_scratch_buffer.inter_transform_sizes = new int[frame_header.rows4x4 + 32][frame_header.columns4x4 + 32];
            frame_scratch_buffer.block_parameters_holder.Reset(frame_header.rows4x4 + 32, frame_header.columns4x4 + 32);
            int tile_count = frame_header.tile_info.tile_count;
            ArrayList<Tile> tiles = new ArrayList<>();
            if (this.is_frame_parallel_) {
               if (frame_scratch_buffer.residual_buffer_pool == null) {
                  frame_scratch_buffer.residual_buffer_pool = new D.ResidualBufferPool(
                     sequence_header.use_128x128_superblock, sequence_header.color_config.subsampling_x, sequence_header.color_config.subsampling_y, 1
                  );
               } else {
                  frame_scratch_buffer.residual_buffer_pool
                     .Reset(sequence_header.use_128x128_superblock, sequence_header.color_config.subsampling_x, sequence_header.color_config.subsampling_y, 1);
               }
            }

            if (threading_strategy.post_filter_thread_pool() != null && do_cdef) {
               int num_units = D.RightShiftWithCeiling(frame_header.rows4x4, 4) * 4;
               frame_scratch_buffer.cdef_border
                  .realloc(
                     sequence_header.color_config.bitdepth,
                     sequence_header.color_config.is_monochrome,
                     frame_header.columns4x4 * 4,
                     num_units,
                     sequence_header.color_config.subsampling_x,
                     0,
                     64,
                     64,
                     64,
                     64
                  );
            }

            if (do_restoration && (do_cdef || threading_strategy.post_filter_thread_pool() != null)) {
               int num_units = D.RightShiftWithCeiling(frame_header.rows4x4, 4) * 4;
               frame_scratch_buffer.loop_restoration_border
                  .realloc(
                     sequence_header.color_config.bitdepth,
                     sequence_header.color_config.is_monochrome,
                     frame_header.upscaled_width,
                     num_units,
                     sequence_header.color_config.subsampling_x,
                     0,
                     64,
                     64,
                     64,
                     64
                  );
            }

            if (do_superres) {
               LogWriter.writeLog("super resolution not supported");
            }

            D.SegmentationMap prev_segment_ids = null;
            if (frame_header.primary_reference_frame == 7) {
               frame_scratch_buffer.symbol_decoder_context.initialize(frame_header.quantizer.base_index);
            } else {
               int index = frame_header.reference_frame_index[frame_header.primary_reference_frame];
               D.RefCountedBuffer prev_frame = state.reference_frame[index];
               frame_scratch_buffer.symbol_decoder_context = prev_frame.FrameContext();
               if (frame_header.segmentation.enabled && prev_frame.columns4x4() == frame_header.columns4x4 && prev_frame.rows4x4() == frame_header.rows4x4) {
                  prev_segment_ids = prev_frame.segmentation_map();
               }
            }

            boolean use_intra_prediction_buffer = this.is_frame_parallel_ || this.settings_.threads == 1;
            if (use_intra_prediction_buffer) {
               D.IntraPredictionBuffer[][] intra_prediction_buffers = frame_scratch_buffer.intra_prediction_buffers.get();

               for (int plane = 0; plane < num_planes; plane++) {
                  int subsampling = plane == 0 ? 0 : sequence_header.color_config.subsampling_x;
                  int intra_prediction_buffer_size = (frame_header.columns4x4 * 4 >> subsampling) * 1;

                  for (int tile_row = 0; tile_row < frame_header.tile_info.tile_rows; tile_row++) {
                     intra_prediction_buffers[tile_row][plane].Resize(intra_prediction_buffer_size);
                  }
               }
            }

            PostFilter post_filter = new PostFilter(
               frame_header, sequence_header, frame_scratch_buffer, current_frame.buffer(), this.settings_.post_filter_mask
            );
            Symbol savedSymbolDecoderContext = new Symbol();
            D.BlockingCounterImpl pending_tiles = new D.BlockingCounterImpl(false);
            pending_tiles.count_ = tile_count;

            for (int tile_number = 0; tile_number < tile_count; tile_number++) {
               Tile tile = Tile.Create(
                  tile_number,
                  tile_buffers.get(tile_number).data,
                  tile_buffers.get(tile_number).offset,
                  tile_buffers.get(tile_number).size,
                  sequence_header,
                  frame_header,
                  current_frame,
                  state,
                  frame_scratch_buffer,
                  this.wedge_masks_,
                  this.quantizer_matrix_,
                  savedSymbolDecoderContext,
                  prev_segment_ids,
                  post_filter,
                  pending_tiles,
                  this.is_frame_parallel_,
                  use_intra_prediction_buffer
               );
               tiles.add(tile);
            }

            if (this.is_frame_parallel_) {
               return this.DecodeTilesFrameParallel(
                  sequence_header, frame_header, tiles, savedSymbolDecoderContext, prev_segment_ids, frame_scratch_buffer, post_filter, current_frame
               );
            } else {
               int status = this.DecodeTilesNonFrameParallel(sequence_header, frame_header, tiles, frame_scratch_buffer, post_filter);
               if (status != 0) {
                  return status;
               } else {
                  if (frame_header.enable_frame_end_update_cdf) {
                     frame_scratch_buffer.symbol_decoder_context = savedSymbolDecoderContext;
                  }

                  current_frame.SetFrameContext(frame_scratch_buffer.symbol_decoder_context);
                  SetSegmentationMap(frame_header, prev_segment_ids, current_frame);
                  return 0;
               }
            }
         }
      }
   }
}
