package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

public class EObu {
   public static void encode(BufferedImage img, OutputStream os, int quality, byte[] exifBytes) throws IOException {
      new EntropyWriter();
      int base_qindex = 256 - (int)Math.ceil(2.55 * quality);
      int iw = img.getWidth();
      int ih = img.getHeight();
      EFrame source = generateSource(img);
      AV1Encoder encoder = new AV1Encoder(iw, ih);
      byte[] sequence_header = encoder.generate_sequence_header();
      byte[] frame_header = encoder.generate_frame_header(base_qindex, false);
      byte[] tile_data = encoder.encode_image(source, base_qindex);
      byte[] av1Data = pack_obus(sequence_header, frame_header, tile_data, true);
      byte[] packed = pack_avif(av1Data, iw, ih, 2, 2, 2, exifBytes);
      os.write(packed);
   }

   private static EFrame generateSource(BufferedImage img) {
      EFrame res = new EFrame(img.getHeight(), img.getWidth());
      int iw = img.getWidth();
      int ih = img.getHeight();
      int y_width = E.roundUp8(img.getWidth());
      int y_height = E.roundUp8(img.getHeight());
      int uv_crop_width = E.round2(img.getWidth(), 1);
      int uv_crop_height = E.round2(img.getHeight(), 1);
      int uv_width = y_width / 2;
      int uv_height = y_height / 2;
      PixGet pg = Access.getPixGet(img);
      int[][] planY = res.y().pixels.data;
      int[][] planU = res.u().pixels.data;
      int[][] planV = res.v().pixels.data;

      for (int y = 0; y < y_height; y++) {
         for (int x = 0; x < y_width; x++) {
            if (x < iw && y < ih) {
               int p = pg.getRGB(x, y);
               int r = p >> 16 & 0xFF;
               int g = p >> 8 & 0xFF;
               int b = p & 0xFF;
               planY[y][x] = 128 + 77 * r + 150 * g + 29 * b >> 8;
            }
         }
      }

      for (int var24 = 0; var24 < y_height; var24 += 2) {
         for (int xx = 0; xx < y_width; xx += 2) {
            if (xx < iw && var24 < ih) {
               int p = pg.getRGB(xx, var24);
               int r = p >> 16 & 0xFF;
               int g = p >> 8 & 0xFF;
               int b = p & 0xFF;
               planU[var24 / 2][xx / 2] = (int)(128.0 - 0.147 * r - 0.289 * g + 0.436 * b);
               planV[var24 / 2][xx / 2] = (int)(128.0 + 0.615 * r - 0.515 * g - 0.1 * b);
            }
         }
      }

      return res;
   }

   static final int get_qctx(int base_qindex) {
      if (base_qindex <= 20) {
         return 0;
      } else if (base_qindex <= 60) {
         return 1;
      } else {
         return base_qindex <= 120 ? 2 : 3;
      }
   }

   static byte[] pack_obus(byte[] sequence_header, byte[] frame_header, byte[] tile_data, boolean include_temporal_delimiter) {
      E.DataBig av1_data = new E.DataBig();
      if (include_temporal_delimiter) {
         av1_data.write_u8(18);
         av1_data.write_u8(0);
      }

      av1_data.write_u8(10);
      av1_data.write_leb128(sequence_header.length);
      av1_data.extend_from_slice(sequence_header);
      av1_data.write_u8(50);
      av1_data.write_leb128(frame_header.length + tile_data.length);
      av1_data.extend_from_slice(frame_header);
      av1_data.extend_from_slice(tile_data);
      return av1_data.finalizeBytes();
   }

   static byte[] pack_avif(
      byte[] av1_data, int crop_width, int crop_height, int color_primaries, int transfer_function, int matrix_coefficients, byte[] exifBytes
   ) {
      ISOBMFFWriter avif = new ISOBMFFWriter();
      int content_size = av1_data.length;
      ISOBMFFBox ftyp = avif.open_box("ftyp".getBytes());
      ftyp.write_bytes("avif".getBytes());
      ftyp.write_u32(0);
      ftyp.write_bytes("avifmif1miafMA1B".getBytes());
      ftyp.drop();
      ISOBMFFBox meta = avif.open_box_with_version("meta".getBytes(), 0, 0);
      ISOBMFFBox hdlr = meta.open_box_with_version("hdlr".getBytes(), 0, 0);
      hdlr.write_u32(0);
      hdlr.write_bytes("pict".getBytes());
      hdlr.write_u32(0);
      hdlr.write_u32(0);
      hdlr.write_u32(0);
      hdlr.write_bytes("jdelavif\u0000".getBytes());
      hdlr.drop();
      ISOBMFFBox pitm = meta.open_box_with_version("pitm".getBytes(), 0, 0);
      pitm.write_u16(1);
      pitm.drop();
      ISOBMFFBox iloc = meta.open_box_with_version("iloc".getBytes(), 0, 0);
      iloc.write_u8(68);
      iloc.write_u8(0);
      iloc.write_u16(1);
      iloc.write_u16(1);
      iloc.write_u16(0);
      iloc.write_u16(1);
      int content_pos_marker = iloc.mark_u32();
      iloc.write_u32(content_size);
      iloc.drop();
      ISOBMFFBox iinf = meta.open_box_with_version("iinf".getBytes(), 0, 0);
      iinf.write_u16(1);
      ISOBMFFBox infe = iinf.open_box_with_version("infe".getBytes(), 2, 0);
      infe.write_u16(1);
      infe.write_u16(0);
      infe.write_bytes("av01".getBytes());
      infe.write_bytes("Color\u0000".getBytes());
      infe.drop();
      iinf.drop();
      infe = meta.open_box("iprp".getBytes());
      ISOBMFFBox ipco = infe.open_box("ipco".getBytes());
      ISOBMFFBox ispe = ipco.open_box_with_version("ispe".getBytes(), 0, 0);
      ispe.write_u32(crop_width);
      ispe.write_u32(crop_height);
      ispe.drop();
      ISOBMFFBox pixi = ipco.open_box_with_version("pixi".getBytes(), 0, 0);
      pixi.write_u8(3);
      pixi.write_u8(8);
      pixi.write_u8(8);
      pixi.write_u8(8);
      pixi.drop();
      ISOBMFFBox av1C = ipco.open_box("av1C".getBytes());
      av1C.write_u8(129);
      av1C.write_u8(31);
      av1C.write_u8(12);
      av1C.write_u8(0);
      av1C.drop();
      ISOBMFFBox colr = ipco.open_box("colr".getBytes());
      colr.write_bytes("nclx".getBytes());
      colr.write_u16(color_primaries);
      colr.write_u16(transfer_function);
      colr.write_u16(matrix_coefficients);
      colr.write_u8(0);
      colr.drop();
      ipco.drop();
      ispe = infe.open_box_with_version("ipma".getBytes(), 0, 0);
      ispe.write_u32(1);
      ispe.write_u16(1);
      ispe.write_u8(4);
      ispe.write_u8(1);
      ispe.write_u8(2);
      ispe.write_u8(131);
      ispe.write_u8(4);
      ispe.drop();
      infe.drop();
      meta.drop();
      hdlr = avif.open_box("mdat".getBytes());
      int content_pos = hdlr.get_file_pos();
      hdlr.write_bytes(av1_data);
      hdlr.drop();
      avif.write_u32_at_marker(content_pos_marker, content_pos);
      return avif.finalizeBytes();
   }

   static class AV1Encoder {
      final int y_width;
      final int y_height;
      final int uv_width;
      final int uv_height;
      final int y_crop_width;
      final int y_crop_height;
      final int uv_crop_width;
      final int uv_crop_height;

      AV1Encoder(int y_crop_width, int y_crop_height) {
         this.y_crop_width = y_crop_width;
         this.y_crop_height = y_crop_height;
         this.y_width = E.roundUp8(y_crop_width);
         this.y_height = E.roundUp8(y_crop_height);
         this.uv_crop_width = E.round2(y_crop_width, 1);
         this.uv_crop_height = E.round2(y_crop_height, 1);
         this.uv_width = this.y_width / 2;
         this.uv_height = this.y_height / 2;
      }

      byte[] generate_sequence_header() {
         EBit w = new EBit();
         w.write_bits(0L, 3);
         w.write_bit(1);
         w.write_bit(1);
         w.write_bits(31L, 5);
         w.write_bits(15L, 4);
         w.write_bits(15L, 4);
         w.write_bits(this.y_crop_width - 1, 16);
         w.write_bits(this.y_crop_height - 1, 16);
         w.write_bits(0L, 6);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bits(0L, 2);
         w.write_bit(0);
         w.write_bit(0);
         return w.finalize(true);
      }

      byte[] generate_frame_header(int base_qindex, boolean add_trailing_one_bit) {
         EBit w = new EBit();
         w.write_bit(1);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bit(1);
         if (this.y_width > 64) {
            w.write_bit(0);
         }

         if (this.y_height > 64) {
            w.write_bit(0);
         }

         w.write_bits(base_qindex, 8);
         w.write_bits(0L, 3);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bits(0L, 6);
         w.write_bits(0L, 6);
         w.write_bits(0L, 3);
         w.write_bit(0);
         w.write_bit(0);
         w.write_bit(1);
         return w.finalize(add_trailing_one_bit);
      }

      byte[] encode_image(EFrame source, int base_qindex) {
         int mi_rows = this.y_height / 4;
         int mi_cols = this.y_width / 4;
         EntropyWriter bitstream = new EntropyWriter();
         E.Array2dModeInfo modeInfo = E.Array2dModeInfo.zeroed(mi_rows, mi_cols);
         EFrame recon = new EFrame(this.y_height, this.y_width);
         TileEncoder tile = new TileEncoder(bitstream, base_qindex, modeInfo, source, recon);
         tile.encode();
         return tile.bitstream.finalizeBytes();
      }
   }

   static class ISOBMFFBox {
      final ISOBMFFWriter w;
      int size_pos;

      ISOBMFFBox(ISOBMFFWriter w, int size_pos) {
         this.w = w;
         this.size_pos = size_pos;
      }

      ISOBMFFBox open_box(byte[] typ) {
         int temp_size_pos = this.w.data.len();
         this.w.data.write_U32(0);
         this.w.data.extend_from_slice(typ);
         return new ISOBMFFBox(this.w, temp_size_pos);
      }

      ISOBMFFBox open_box_with_version(byte[] typ, int version, int flags) {
         int temp_size_pos = this.w.data.len();
         this.w.data.write_U32(0);
         this.w.data.extend_from_slice(typ);
         this.w.data.write_U32(version << 24 | flags);
         return new ISOBMFFBox(this.w, temp_size_pos);
      }

      int get_file_pos() {
         return this.w.data.len();
      }

      int mark_u32() {
         int marker = this.w.data.len();
         this.write_u32(0);
         return marker;
      }

      void write_u8(int value) {
         this.w.data.write_u8(value);
      }

      void write_u16(int value) {
         this.w.data.write_u16(value);
      }

      void write_u32(int value) {
         this.w.data.write_U32(value);
      }

      void write_bytes(byte[] value) {
         this.w.data.extend_from_slice(value);
      }

      void drop() {
         int cur_pos = this.w.data.len();
         int total_size = cur_pos - this.size_pos;
         this.w.data.pos_u8(this.size_pos, total_size >> 24 & 0xFF);
         this.w.data.pos_u8(this.size_pos + 1, total_size >> 16 & 0xFF);
         this.w.data.pos_u8(this.size_pos + 2, total_size >> 8 & 0xFF);
         this.w.data.pos_u8(this.size_pos + 3, total_size & 0xFF);
      }
   }

   static class ISOBMFFWriter {
      E.DataBig data = new E.DataBig();

      ISOBMFFBox open_box(byte[] typ) {
         int size_pos = this.data.len();
         this.data.write_U32(0);
         this.data.extend_from_slice(typ);
         return new ISOBMFFBox(this, size_pos);
      }

      ISOBMFFBox open_box_with_version(byte[] typ, int version, int flags) {
         int size_pos = this.data.len();
         this.data.write_U32(0);
         this.data.extend_from_slice(typ);
         this.data.write_U32(version << 24 | flags);
         return new ISOBMFFBox(this, size_pos);
      }

      int get_file_pos() {
         return this.data.len();
      }

      int mark_u32() {
         int marker = this.data.len();
         this.write_u32(0);
         return marker;
      }

      void write_u8(int value) {
         this.data.write_u8(value);
      }

      void write_u16(int value) {
         this.data.write_u16(value);
      }

      void write_u32(int value) {
         this.data.write_U32(value);
      }

      void write_bytes(byte[] value) {
         this.data.extend_from_slice(value);
      }

      byte[] finalizeBytes() {
         return this.data.finalizeBytes();
      }

      void write_u32_at_marker(int pos, int value) {
         this.data.pos_u8(pos, value >> 24 & 0xFF);
         this.data.pos_u8(pos + 1, value >> 16 & 0xFF);
         this.data.pos_u8(pos + 2, value >> 8 & 0xFF);
         this.data.pos_u8(pos + 3, value & 0xFF);
      }
   }

   static class ModeInfo {
      int[] level_ctx = new int[]{0, 0, 0};
      int[] dc_sign = new int[]{0, 0, 0};
   }

   static class TileEncoder {
      final EntropyWriter bitstream;
      final int base_qindex;
      final E.Array2dModeInfo mode_info;
      final EFrame source;
      final EFrame recon;

      TileEncoder(EntropyWriter bitstream, int base_qindex, E.Array2dModeInfo mode_info, EFrame source, EFrame recon) {
         this.bitstream = bitstream;
         this.base_qindex = base_qindex;
         this.mode_info = mode_info;
         this.source = source;
         this.recon = recon;
      }

      void encode() {
         int mi_rows = this.mode_info.rows;
         int mi_cols = this.mode_info.cols;
         int sb_rows = (int)Math.ceil(mi_rows / 16.0);
         int sb_cols = (int)Math.ceil(mi_cols / 16.0);

         for (int sb_row = 0; sb_row < sb_rows; sb_row++) {
            for (int sb_col = 0; sb_col < sb_cols; sb_col++) {
               this.encode_superblock(sb_row, sb_col);
            }
         }
      }

      void encode_superblock(int sb_row, int sb_col) {
         int mi_row = sb_row * 16;
         int mi_col = sb_col * 16;
         this.encode_partition(mi_row, mi_col, 64);
      }

      void encode_partition(int mi_row, int mi_col, int bsize) {
         if (bsize == 8) {
            this.bitstream.write_symbol(0, ECdf.partition_8x8_cdf);
            this.encode_block(mi_row, mi_col, bsize);
         } else {
            int mi_rows = this.mode_info.rows;
            int mi_cols = this.mode_info.cols;
            int sub_rows = mi_row + bsize / 8 < mi_rows ? 2 : 1;
            int sub_cols = mi_col + bsize / 8 < mi_cols ? 2 : 1;
            int above_ctx = mi_row > 0 ? 1 : 0;
            int left_ctx = mi_col > 0 ? 1 : 0;
            int ctx = 2 * left_ctx + above_ctx;
            int[] cdf = new int[]{0};
            if (bsize == 16) {
               cdf = ECdf.partition_16x16_cdf[ctx];
            } else if (bsize == 32) {
               cdf = ECdf.partition_32x32_cdf[ctx];
            } else if (bsize == 64) {
               cdf = ECdf.partition_64x64_cdf[ctx];
            }

            if (sub_rows > 1 && sub_cols > 1) {
               this.bitstream.write_symbol(3, cdf);
            } else if (sub_cols > 1) {
               int p_split = E.get_prob(2, cdf) + E.get_prob(3, cdf) + E.get_prob(4, cdf) + E.get_prob(6, cdf) + E.get_prob(7, cdf) + E.get_prob(9, cdf);
               this.bitstream.write_bit(1, 32768 - p_split);
            } else if (sub_rows > 1) {
               int p_split = E.get_prob(1, cdf) + E.get_prob(3, cdf) + E.get_prob(4, cdf) + E.get_prob(5, cdf) + E.get_prob(6, cdf) + E.get_prob(8, cdf);
               this.bitstream.write_bit(1, 32768 - p_split);
            }

            int offset = bsize / 8;

            for (int i = 0; i < sub_rows; i++) {
               for (int j = 0; j < sub_cols; j++) {
                  this.encode_partition(mi_row + i * offset, mi_col + j * offset, bsize / 2);
               }
            }
         }
      }

      void encode_block(int mi_row, int mi_col, int bsize) {
         ModeInfo this_mi = new ModeInfo();
         this.bitstream.write_symbol(0, ECdf.skip_cdf);
         this.bitstream.write_symbol(0, ECdf.y_mode_cdf);
         this.bitstream.write_symbol(0, ECdf.uv_mode_cdf);

         for (int plane = 0; plane < 3; plane++) {
            int subsampling = plane > 0 ? 1 : 0;
            int y0 = mi_row * 4 >> subsampling;
            int x0 = mi_col * 4 >> subsampling;
            int h = bsize >> subsampling;
            int w = bsize >> subsampling;
            ERecon.dc_predict(this.recon.plane(plane).pixels, y0, x0, h, w);
            E.Array2dInt residual = ERecon.compute_residual(this.source.plane(plane).pixels, this.recon.plane(plane).pixels, y0, x0, h, w);
            ERecon.quantize(residual, this.base_qindex);
            this.encode_coeffs(plane, mi_row, mi_col, bsize, this_mi, residual);
            ERecon.dequantize(residual, this.base_qindex);
            ERecon.apply_residual(this.recon.plane(plane).pixels, residual, y0, x0, h, w);
         }

         this.mode_info.fill_region(mi_row, mi_col, bsize / 4, bsize / 4, this_mi);
      }

      void encode_coeffs(int plane, int mi_row, int mi_col, int bsize, ModeInfo this_mi, E.Array2dInt coeffs) {
         int txsize = plane > 0 ? bsize / 2 : bsize;
         int txs_ctx = txsize == 8 ? 1 : 0;
         int num_coeffs = txsize * txsize;
         int[][] scan = E.scan_order_2d[txs_ctx];
         int qctx = EObu.get_qctx(this.base_qindex);
         int ptype = plane == 0 ? 0 : 1;
         int eob = 0;
         int culLevel = 0;

         for (int c = 0; c < num_coeffs; c++) {
            int[] rowcol = scan[c];
            int row = rowcol[0];
            int col = rowcol[1];
            int coeff = coeffs.data[row][col];
            culLevel += Math.abs(coeff);
            if (coeff != 0) {
               eob = c + 1;
            }
         }

         this_mi.level_ctx[plane] = Math.min(culLevel, 63);
         boolean all_zero = eob == 0;
         int all_zero_ctx;
         if (plane == 0) {
            all_zero_ctx = 0;
         } else {
            boolean above = false;
            boolean left = false;
            if (mi_row > 0) {
               ModeInfo above_block = this.mode_info.data[mi_row - 1][mi_col];
               above |= above_block.level_ctx[plane] != 0;
               above |= above_block.dc_sign[plane] != 0;
            }

            if (mi_col > 0) {
               ModeInfo left_block = this.mode_info.data[mi_row][mi_col - 1];
               left |= left_block.level_ctx[plane] != 0;
               left |= left_block.dc_sign[plane] != 0;
            }

            all_zero_ctx = 7 + (above ? 1 : 0) + (left ? 1 : 0);
         }

         this.bitstream.write_symbol(all_zero ? 1 : 0, ECdf.all_zero_cdf[qctx][txs_ctx][all_zero_ctx]);
         if (!all_zero) {
            if (plane == 0) {
               this.bitstream.write_symbol(1, ECdf.tx_type_cdf);
            }

            int eob_class = E.CeilLog2(eob);
            int[] eob_class_cdf = plane == 0 ? ECdf.eob_class_64_cdf[qctx][ptype] : ECdf.eob_class_16_cdf[qctx][ptype];
            this.bitstream.write_symbol(eob_class, eob_class_cdf);
            if (eob_class > 1) {
               int eob_class_low = (1 << eob_class - 1) + 1;
               int eob_class_hi = 1 << eob_class;
               int[] first_extra_bit_cdf;
               if (plane == 0) {
                  first_extra_bit_cdf = ECdf.eob_extra_8x8_cdf[qctx][ptype][eob_class - 2];
               } else {
                  first_extra_bit_cdf = ECdf.eob_extra_4x4_cdf[qctx][ptype][eob_class - 2];
               }

               int eob_shift = eob_class - 2;
               int extra_bit = eob - eob_class_low >> eob_shift & 1;
               this.bitstream.write_symbol(extra_bit, first_extra_bit_cdf);
               int remainder = eob - eob_class_low - (extra_bit << eob_shift);
               int remainder_bits = eob_class - 2;
               this.bitstream.write_literal(remainder, remainder_bits);
            }

            for (int cx = eob - 1; cx >= 0; cx--) {
               int[] rowscol = scan[cx];
               int row = rowscol[0];
               int col = rowscol[1];
               int coeff = coeffs.data[row][col];
               int abs_value = Math.abs(coeff);
               if (cx == eob - 1) {
                  int base_eob_ctx;
                  if (cx == 0) {
                     base_eob_ctx = 0;
                  } else if (cx <= num_coeffs / 8) {
                     base_eob_ctx = 1;
                  } else if (cx <= num_coeffs / 4) {
                     base_eob_ctx = 2;
                  } else {
                     base_eob_ctx = 3;
                  }

                  int coded_value = Math.min(abs_value - 1, 2);
                  this.bitstream.write_symbol(coded_value, ECdf.coeff_base_eob_cdf[qctx][txs_ctx][ptype][base_eob_ctx]);
               } else {
                  int base_ctx;
                  if (cx == 0) {
                     base_ctx = 0;
                  } else {
                     int mag = 0;

                     for (int[] rowcoloff : E.Sig_Ref_Diff_Offset) {
                        int row_off = rowcoloff[0];
                        int col_off = rowcoloff[1];
                        int ref_row = row + row_off;
                        int ref_col = col + col_off;
                        if (ref_row < txsize && ref_col < txsize) {
                           mag += Math.min(Math.abs(coeffs.data[ref_row][ref_col]), 3);
                        }
                     }

                     int mag_part = Math.min(E.round2(mag, 1), 4);
                     int loc_part = E.Coeff_Base_Ctx_Offset_8x8[Math.min(row, 4)][Math.min(col, 4)];
                     base_ctx = mag_part + loc_part;
                  }

                  int coded_value = Math.min(abs_value, 3);
                  this.bitstream.write_symbol(coded_value, ECdf.coeff_base_cdf[qctx][txs_ctx][ptype][base_ctx]);
               }

               if (abs_value > 2) {
                  int mag = 0;

                  for (int[] rowcoloffx : E.Mag_Ref_Offset) {
                     int row_off = rowcoloffx[0];
                     int col_off = rowcoloffx[1];
                     int ref_row = row + row_off;
                     int ref_col = col + col_off;
                     if (ref_row < txsize && ref_col < txsize) {
                        mag += Math.min(Math.abs(coeffs.data[ref_row][ref_col]), 15);
                     }
                  }

                  int mag_part = Math.min(E.round2(mag, 1), 6);
                  int loc_part;
                  if (cx == 0) {
                     loc_part = 0;
                  } else if (row < 2 && col < 2) {
                     loc_part = 7;
                  } else {
                     loc_part = 14;
                  }

                  int br_ctx = mag_part + loc_part;
                  int level = 3;

                  for (int i = 0; i < 4; i++) {
                     int coeff_br = Math.min(abs_value - level, 3);
                     this.bitstream.write_symbol(coeff_br, ECdf.coeff_br_cdf[qctx][txs_ctx][ptype][br_ctx]);
                     level += coeff_br;
                     if (coeff_br < 3) {
                        break;
                     }
                  }
               }
            }

            int dc_coeff = coeffs.data[0][0];
            if (dc_coeff != 0) {
               int net_neighbour_sign = 0;
               if (mi_row > 0) {
                  net_neighbour_sign += this.mode_info.data[mi_row - 1][mi_col].dc_sign[plane];
               }

               if (mi_col > 0) {
                  net_neighbour_sign += this.mode_info.data[mi_row][mi_col - 1].dc_sign[plane];
               }

               int dc_sign_ctx;
               if (net_neighbour_sign == 0) {
                  dc_sign_ctx = 0;
               } else if (net_neighbour_sign < 0) {
                  dc_sign_ctx = 1;
               } else {
                  dc_sign_ctx = 2;
               }

               int sign = dc_coeff < 0 ? 1 : 0;
               this.bitstream.write_symbol(sign, ECdf.dc_sign_cdf[qctx][ptype][dc_sign_ctx]);
            }

            if (Math.abs(dc_coeff) >= 15) {
               this.bitstream.write_golomb(Math.abs(dc_coeff) - 15);
            }

            this_mi.dc_sign[plane] = (int)Math.signum((float)dc_coeff);

            for (int cx = 1; cx < eob; cx++) {
               int[] rowcol = scan[cx];
               int rowx = rowcol[0];
               int colx = rowcol[1];
               int coeffx = coeffs.data[rowx][colx];
               if (coeffx != 0) {
                  int sign = coeffx < 0 ? 1 : 0;
                  this.bitstream.write_literal(sign, 1);
               }

               if (Math.abs(coeffx) >= 15) {
                  this.bitstream.write_golomb(Math.abs(coeffx) - 15);
               }
            }
         }
      }
   }
}
