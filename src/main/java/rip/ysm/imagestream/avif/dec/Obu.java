package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.avif.dec.D;
import rip.ysm.imagestream.avif.dec.Mem;
import rip.ysm.imagestream.avif.dec.Quant;
import rip.ysm.imagestream.avif.dec.RawBit;
import java.util.ArrayList;
import java.util.Arrays;
import rip.ysm.imagestream.internal.LogWriter;

class Obu {
    int operating_point_;
    int size_;
    RawBit bitReader;
    byte[] data_;
    int dataOffset_;
    D.ObuSequenceHeader sequence_header_ = new D.ObuSequenceHeader();
    D.ObuFrameHeader frame_header_ = new D.ObuFrameHeader();
    ArrayList<D.TileBuffer> tile_buffers_ = new ArrayList();
    ArrayList<D.ObuHeader> obu_headers_ = new ArrayList();
    final D.DecoderState decoder_state_;
    final D.BufferPool buffer_pool_;
    int next_tile_group_start_ = 0;
    boolean has_sequence_header_ = false;
    boolean sequence_header_changed_ = false;
    boolean extension_disallowed_ = false;
    D.RefCountedBuffer current_frame_;

    Obu(byte[] data, int size, int operating_point, D.BufferPool buffer_pool, D.DecoderState decoder_state) {
        this.data_ = data;
        this.size_ = size;
        this.operating_point_ = operating_point;
        this.buffer_pool_ = buffer_pool;
        this.decoder_state_ = decoder_state;
    }

    D.ObuSequenceHeader sequence_header() {
        return this.sequence_header_;
    }

    D.ObuFrameHeader frame_header() {
        return this.frame_header_;
    }

    private static int tileLog2(int block_size, int target) {
        int k = 0;
        while (block_size << k < target) {
            ++k;
        }
        return k;
    }

    private static void parseBitStreamLevel(D.BitStreamLevel level, int level_bits) {
        level.major = 2 + (level_bits >> 2);
        level.minor = level_bits & 3;
    }

    private static void setDefaultRefDeltas(D.LoopFilter loop_filter) {
        loop_filter.ref_deltas[0] = 1;
        loop_filter.ref_deltas[4] = -1;
        loop_filter.ref_deltas[7] = -1;
        loop_filter.ref_deltas[6] = -1;
    }

    private static boolean inTemporalLayer(int operating_point_idc, int temporal_id) {
        return (operating_point_idc >> temporal_id & 1) != 0;
    }

    private static boolean inSpatialLayer(int operating_point_idc, int spatial_id) {
        return (operating_point_idc >> spatial_id + 8 & 1) != 0;
    }

    private static int getLastNonzeroByteIndex(byte[] data, int dataOffset, int size) {
        int i;
        if (size > Integer.MAX_VALUE) {
            return -1;
        }
        for (i = size - 1; i >= 0 && (data[dataOffset + i] & 0xFF) == 0; --i) {
        }
        return i;
    }

    private boolean parseColorConfig(D.ObuSequenceHeader sequence_header) {
        boolean color_description_present_flag;
        boolean high_bitdepth;
        D.ColorConfig color_config = sequence_header.color_config;
        long scratch = this.bitReader.readBit();
        boolean bl = high_bitdepth = scratch != 0L;
        if (sequence_header.profile == 2 && high_bitdepth) {
            scratch = this.bitReader.readBit();
            boolean is_twelve_bit = scratch != 0L;
            color_config.bitdepth = is_twelve_bit ? 12 : 10;
        } else {
            int n = color_config.bitdepth = high_bitdepth ? 10 : 8;
        }
        color_config.is_monochrome = sequence_header.profile == 1 ? false : (scratch = (long)this.bitReader.readBit()) != 0L;
        scratch = this.bitReader.readBit();
        boolean bl2 = color_description_present_flag = scratch != 0L;
        if (color_description_present_flag) {
            scratch = this.bitReader.readLiteral(8);
            color_config.color_primary = (int)scratch;
            scratch = this.bitReader.readLiteral(8);
            color_config.transfer_characteristics = (int)scratch;
            scratch = this.bitReader.readLiteral(8);
            color_config.matrix_coefficients = (int)scratch;
        } else {
            color_config.color_primary = 2;
            color_config.transfer_characteristics = 2;
            color_config.matrix_coefficients = 2;
        }
        if (color_config.is_monochrome) {
            scratch = this.bitReader.readBit();
            color_config.color_range = (int)scratch;
            color_config.subsampling_x = 1;
            color_config.subsampling_y = 1;
            color_config.chroma_sample_position = 0;
        } else {
            if (color_config.color_primary == 1 && color_config.transfer_characteristics == 13 && color_config.matrix_coefficients == 0) {
                color_config.color_range = 1;
                color_config.subsampling_x = 0;
                color_config.subsampling_y = 0;
                if (sequence_header.profile != 1 && (sequence_header.profile != 2 || color_config.bitdepth != 12)) {
                    return false;
                }
            } else {
                scratch = this.bitReader.readBit();
                color_config.color_range = (int)scratch;
                if (sequence_header.profile == 0) {
                    color_config.subsampling_x = 1;
                    color_config.subsampling_y = 1;
                } else if (sequence_header.profile == 1) {
                    color_config.subsampling_x = 0;
                    color_config.subsampling_y = 0;
                } else if (color_config.bitdepth == 12) {
                    scratch = this.bitReader.readBit();
                    color_config.subsampling_x = (int)scratch;
                    if (color_config.subsampling_x == 1) {
                        scratch = this.bitReader.readBit();
                        color_config.subsampling_y = (int)scratch;
                    } else {
                        color_config.subsampling_y = 0;
                    }
                } else {
                    color_config.subsampling_x = 1;
                    color_config.subsampling_y = 0;
                }
                if (color_config.subsampling_x == 1 && color_config.subsampling_y == 1) {
                    scratch = this.bitReader.readLiteral(2);
                    color_config.chroma_sample_position = (int)scratch;
                }
            }
            boolean bl3 = color_config.separate_uv_delta_q = (scratch = (long)this.bitReader.readBit()) != 0L;
        }
        if (color_config.matrix_coefficients == 0 && (color_config.subsampling_x != 0 || color_config.subsampling_y != 0)) {
            LogWriter.writeLog("OBU: color config error");
            return false;
        }
        return true;
    }

    private boolean parseTimingInfo(D.ObuSequenceHeader sequence_header) {
        long scratch = this.bitReader.readBit();
        boolean bl = sequence_header.timing_info_present_flag = scratch != 0L;
        if (!sequence_header.timing_info_present_flag) {
            return true;
        }
        D.TimingInfo info = sequence_header.timing_info;
        scratch = this.bitReader.readLiteral(32);
        info.num_units_in_tick = (int)scratch;
        if (info.num_units_in_tick == 0) {
            return false;
        }
        scratch = this.bitReader.readLiteral(32);
        info.time_scale = (int)scratch;
        scratch = this.bitReader.readBit();
        boolean bl2 = info.equal_picture_interval = scratch != 0L;
        if (info.equal_picture_interval) {
            info.num_ticks_per_picture = this.bitReader.readUvlc() + 1;
        }
        return true;
    }

    private boolean parseDecoderModelInfo(D.ObuSequenceHeader sequence_header) {
        if (!sequence_header.timing_info_present_flag) {
            return true;
        }
        long scratch = this.bitReader.readBit();
        boolean bl = sequence_header.decoder_model_info_present_flag = scratch != 0L;
        if (!sequence_header.decoder_model_info_present_flag) {
            return true;
        }
        D.DecoderModelInfo info = sequence_header.decoder_model_info;
        scratch = this.bitReader.readLiteral(5);
        info.encoder_decoder_buffer_delay_length = (int)(1L + scratch);
        scratch = this.bitReader.readLiteral(32);
        info.num_units_in_decoding_tick = (int)scratch;
        scratch = this.bitReader.readLiteral(5);
        info.buffer_removal_time_length = (int)(1L + scratch);
        scratch = this.bitReader.readLiteral(5);
        info.frame_presentation_time_length = (int)(1L + scratch);
        return true;
    }

    private boolean parseOperatingParameters(D.ObuSequenceHeader sequence_header, int index) {
        long scratch = this.bitReader.readBit();
        boolean bl = sequence_header.decoder_model_present_for_operating_point[index] = scratch != 0L;
        if (!sequence_header.decoder_model_present_for_operating_point[index]) {
            return true;
        }
        D.OperatingParameters params = sequence_header.operating_parameters;
        scratch = this.bitReader.readLiteral(sequence_header.decoder_model_info.encoder_decoder_buffer_delay_length);
        params.decoder_buffer_delay[index] = (int)scratch;
        scratch = this.bitReader.readLiteral(sequence_header.decoder_model_info.encoder_decoder_buffer_delay_length);
        params.encoder_buffer_delay[index] = (int)scratch;
        scratch = this.bitReader.readBit();
        params.low_delay_mode_flag[index] = scratch != 0L;
        return true;
    }

    private boolean parseSequenceHeader(boolean seen_frame_header) {
        D.ObuSequenceHeader sequence_header = new D.ObuSequenceHeader();
        long scratch = this.bitReader.readLiteral(3);
        if (scratch >= 3L) {
            LogWriter.writeLog("OBU: max profile exceeded");
            return false;
        }
        sequence_header.profile = (int)scratch;
        scratch = this.bitReader.readBit();
        sequence_header.still_picture = scratch != 0L;
        scratch = this.bitReader.readBit();
        boolean bl = sequence_header.reduced_still_picture_header = scratch != 0L;
        if (sequence_header.reduced_still_picture_header) {
            if (!sequence_header.still_picture) {
                return false;
            }
            sequence_header.operating_points = 1;
            sequence_header.operating_point_idc[0] = 0;
            scratch = this.bitReader.readLiteral(5);
            Obu.parseBitStreamLevel(sequence_header.level[0], (int)scratch);
        } else {
            if (!this.parseTimingInfo(sequence_header) || !this.parseDecoderModelInfo(sequence_header)) {
                return false;
            }
            scratch = this.bitReader.readBit();
            boolean initial_display_delay_present_flag = scratch != 0L;
            scratch = this.bitReader.readLiteral(5);
            sequence_header.operating_points = (int)(1L + scratch);
            if (this.operating_point_ >= sequence_header.operating_points) {
                return false;
            }
            for (int i = 0; i < sequence_header.operating_points; ++i) {
                scratch = this.bitReader.readLiteral(12);
                sequence_header.operating_point_idc[i] = (int)scratch;
                for (int j = 0; j < i; ++j) {
                    if (sequence_header.operating_point_idc[i] != sequence_header.operating_point_idc[j]) continue;
                    return false;
                }
                scratch = this.bitReader.readLiteral(5);
                Obu.parseBitStreamLevel(sequence_header.level[i], (int)scratch);
                if (sequence_header.level[i].major > 3) {
                    scratch = this.bitReader.readBit();
                    sequence_header.tier[i] = (int)scratch;
                }
                if (sequence_header.decoder_model_info_present_flag && !this.parseOperatingParameters(sequence_header, i)) {
                    return false;
                }
                if (!initial_display_delay_present_flag || (scratch = (long)this.bitReader.readBit()) == 0L) continue;
                scratch = this.bitReader.readLiteral(4);
                sequence_header.initial_display_delay[i] = (int)(1L + scratch);
            }
        }
        scratch = this.bitReader.readLiteral(4);
        sequence_header.frame_width_bits = (int)(1L + scratch);
        scratch = this.bitReader.readLiteral(4);
        sequence_header.frame_height_bits = (int)(1L + scratch);
        scratch = this.bitReader.readLiteral(sequence_header.frame_width_bits);
        sequence_header.max_frame_width = (int)(1L + scratch);
        scratch = this.bitReader.readLiteral(sequence_header.frame_height_bits);
        sequence_header.max_frame_height = (int)(1L + scratch);
        if (!sequence_header.reduced_still_picture_header) {
            scratch = this.bitReader.readBit();
            boolean bl2 = sequence_header.frame_id_numbers_present = scratch != 0L;
        }
        if (sequence_header.frame_id_numbers_present) {
            scratch = this.bitReader.readLiteral(4);
            sequence_header.delta_frame_id_length_bits = (int)(2L + scratch);
            scratch = this.bitReader.readLiteral(3);
            sequence_header.frame_id_length_bits = (int)((long)(sequence_header.delta_frame_id_length_bits + 1) + scratch);
            if (sequence_header.frame_id_length_bits > 16) {
                LogWriter.writeLog("OBU: max frameidlength bits exceeded");
                return false;
            }
        }
        sequence_header.use_128x128_superblock = (scratch = (long)this.bitReader.readBit()) != 0L;
        scratch = this.bitReader.readBit();
        sequence_header.enable_filter_intra = scratch != 0L;
        scratch = this.bitReader.readBit();
        boolean bl3 = sequence_header.enable_intra_edge_filter = scratch != 0L;
        if (sequence_header.reduced_still_picture_header) {
            sequence_header.force_screen_content_tools = 2;
            sequence_header.force_integer_mv = 2;
        } else {
            scratch = this.bitReader.readBit();
            sequence_header.enable_interintra_compound = scratch != 0L;
            scratch = this.bitReader.readBit();
            sequence_header.enable_masked_compound = scratch != 0L;
            scratch = this.bitReader.readBit();
            sequence_header.enable_warped_motion = scratch != 0L;
            scratch = this.bitReader.readBit();
            sequence_header.enable_dual_filter = scratch != 0L;
            scratch = this.bitReader.readBit();
            boolean bl4 = sequence_header.enable_order_hint = scratch != 0L;
            if (sequence_header.enable_order_hint) {
                scratch = this.bitReader.readBit();
                sequence_header.enable_jnt_comp = scratch != 0L;
                scratch = this.bitReader.readBit();
                sequence_header.enable_ref_frame_mvs = scratch != 0L;
            }
            boolean bl5 = sequence_header.choose_screen_content_tools = (scratch = (long)this.bitReader.readBit()) != 0L;
            if (sequence_header.choose_screen_content_tools) {
                sequence_header.force_screen_content_tools = 2;
            } else {
                scratch = this.bitReader.readBit();
                sequence_header.force_screen_content_tools = (int)scratch;
            }
            if (sequence_header.force_screen_content_tools > 0) {
                scratch = this.bitReader.readBit();
                boolean bl6 = sequence_header.choose_integer_mv = scratch != 0L;
                if (sequence_header.choose_integer_mv) {
                    sequence_header.force_integer_mv = 2;
                } else {
                    scratch = this.bitReader.readBit();
                    sequence_header.force_integer_mv = (int)scratch;
                }
            } else {
                sequence_header.force_integer_mv = 2;
            }
            if (sequence_header.enable_order_hint) {
                scratch = this.bitReader.readLiteral(3);
                sequence_header.order_hint_bits = (int)(1L + scratch);
                sequence_header.order_hint_shift_bits = D.Mod32(32 - sequence_header.order_hint_bits);
            }
        }
        scratch = this.bitReader.readBit();
        sequence_header.enable_superres = scratch != 0L;
        scratch = this.bitReader.readBit();
        sequence_header.enable_cdef = scratch != 0L;
        scratch = this.bitReader.readBit();
        boolean bl7 = sequence_header.enable_restoration = scratch != 0L;
        if (!this.parseColorConfig(sequence_header)) {
            LogWriter.writeLog("OBU: parse color config error");
            return false;
        }
        scratch = this.bitReader.readBit();
        boolean bl8 = sequence_header.film_grain_params_present = scratch != 0L;
        if (this.has_sequence_header_ && sequence_header.ParametersChanged(this.sequence_header_)) {
            if (seen_frame_header) {
                return false;
            }
            this.sequence_header_changed_ = true;
            this.decoder_state_.ClearReferenceFrames();
        }
        this.sequence_header_ = sequence_header;
        if (!this.has_sequence_header_) {
            this.sequence_header_changed_ = true;
        }
        this.has_sequence_header_ = true;
        this.extension_disallowed_ = this.sequence_header_.operating_point_idc[this.operating_point_] == 0;
        return true;
    }

    void markInvalidReferenceFrames() {
        int lower_bound = this.decoder_state_.current_frame_id - (1 << this.sequence_header_.delta_frame_id_length_bits);
        boolean lower_bound_is_smaller = true;
        if (lower_bound <= 0) {
            lower_bound += 1 << this.sequence_header_.frame_id_length_bits;
            lower_bound_is_smaller = false;
        }
        for (int i = 0; i < 8; ++i) {
            int reference_frame_id = this.decoder_state_.reference_frame_id[i];
            if (lower_bound_is_smaller) {
                if (reference_frame_id <= this.decoder_state_.current_frame_id && reference_frame_id >= lower_bound) continue;
                this.decoder_state_.reference_frame[i] = null;
                continue;
            }
            if (reference_frame_id <= this.decoder_state_.current_frame_id || reference_frame_id >= lower_bound) continue;
            this.decoder_state_.reference_frame[i] = null;
        }
    }

    private boolean parseFrameSizeAndRenderSize() {
        long scratch;
        if (this.frame_header_.frame_size_override_flag) {
            scratch = this.bitReader.readLiteral(this.sequence_header_.frame_width_bits);
            this.frame_header_.width = (int)(1L + scratch);
            scratch = this.bitReader.readLiteral(this.sequence_header_.frame_height_bits);
            this.frame_header_.height = (int)(1L + scratch);
            if (this.frame_header_.width > this.sequence_header_.max_frame_width || this.frame_header_.height > this.sequence_header_.max_frame_height) {
                return false;
            }
        } else {
            this.frame_header_.width = this.sequence_header_.max_frame_width;
            this.frame_header_.height = this.sequence_header_.max_frame_height;
        }
        if (!this.parseSuperResParametersAndComputeImageSize()) {
            return false;
        }
        scratch = this.bitReader.readBit();
        boolean bl = this.frame_header_.render_and_frame_size_different = scratch != 0L;
        if (this.frame_header_.render_and_frame_size_different) {
            scratch = this.bitReader.readLiteral(16);
            this.frame_header_.render_width = (int)(1L + scratch);
            scratch = this.bitReader.readLiteral(16);
            this.frame_header_.render_height = (int)(1L + scratch);
        } else {
            this.frame_header_.render_width = this.frame_header_.upscaled_width;
            this.frame_header_.render_height = this.frame_header_.height;
        }
        return true;
    }

    private boolean parseSuperResParametersAndComputeImageSize() {
        long scratch;
        this.frame_header_.upscaled_width = this.frame_header_.width;
        this.frame_header_.use_superres = false;
        if (this.sequence_header_.enable_superres) {
            scratch = this.bitReader.readBit();
            boolean bl = this.frame_header_.use_superres = scratch != 0L;
        }
        if (this.frame_header_.use_superres) {
            scratch = this.bitReader.readLiteral(3);
            this.frame_header_.superres_scale_denominator = (int)(scratch + 9L);
            this.frame_header_.width = (this.frame_header_.upscaled_width * 8 + this.frame_header_.superres_scale_denominator / 2) / this.frame_header_.superres_scale_denominator;
        } else {
            this.frame_header_.superres_scale_denominator = 8;
        }
        if (this.frame_header_.upscaled_width > Integer.MAX_VALUE / this.frame_header_.height) {
            return false;
        }
        this.frame_header_.columns4x4 = this.frame_header_.width + 7 >> 3 << 1;
        this.frame_header_.rows4x4 = this.frame_header_.height + 7 >> 3 << 1;
        return true;
    }

    private boolean validateInterFrameSize() {
        for (int index : this.frame_header_.reference_frame_index) {
            D.RefCountedBuffer reference_frame = this.decoder_state_.reference_frame[index];
            if (2 * this.frame_header_.width >= reference_frame.upscaled_width() && 2 * this.frame_header_.height >= reference_frame.frame_height() && this.frame_header_.width <= 16 * reference_frame.upscaled_width() && this.frame_header_.height <= 16 * reference_frame.frame_height()) continue;
            return false;
        }
        return true;
    }

    private boolean parseReferenceOrderHint() {
        if (!this.frame_header_.error_resilient_mode || !this.sequence_header_.enable_order_hint) {
            return true;
        }
        for (int i = 0; i < 8; ++i) {
            long scratch = this.bitReader.readLiteral(this.sequence_header_.order_hint_bits);
            this.frame_header_.reference_order_hint[i] = (int)scratch;
            if (this.frame_header_.reference_order_hint[i] == this.decoder_state_.reference_order_hint[i]) continue;
            this.decoder_state_.reference_frame[i] = null;
        }
        return true;
    }

    private static int findLatestBackwardReference(int currentFrameHint, int[] shiftedOrderHints, boolean[] usedFrame) {
        int ref = -1;
        int latest_order_hint = Integer.MIN_VALUE;
        for (int i = 0; i < 8; ++i) {
            int hint = shiftedOrderHints[i];
            if (usedFrame[i] || hint < currentFrameHint || hint < latest_order_hint) continue;
            ref = i;
            latest_order_hint = hint;
        }
        return ref;
    }

    private static int findLatestForwardReference(int currentFrameHint, int[] shiftedOrderHints, boolean[] usedFrame) {
        int ref = -1;
        int latest_order_hint = Integer.MIN_VALUE;
        for (int i = 0; i < 8; ++i) {
            int hint = shiftedOrderHints[i];
            if (usedFrame[i] || hint >= currentFrameHint || hint < latest_order_hint) continue;
            ref = i;
            latest_order_hint = hint;
        }
        return ref;
    }

    private static int findEarliestBackwardReference(int currentFrameHint, int[] shiftedOrderHints, boolean[] usedFrame) {
        int ref = -1;
        int earliest_order_hint = Integer.MAX_VALUE;
        for (int i = 0; i < 8; ++i) {
            int hint = shiftedOrderHints[i];
            if (usedFrame[i] || hint < currentFrameHint || hint >= earliest_order_hint) continue;
            ref = i;
            earliest_order_hint = hint;
        }
        return ref;
    }

    private static int findReferenceWithSmallestOutputOrder(int[] shiftedOrderHints) {
        int ref = -1;
        int earliest_order_hint = Integer.MAX_VALUE;
        for (int i = 0; i < 8; ++i) {
            int hint = shiftedOrderHints[i];
            if (hint >= earliest_order_hint) continue;
            ref = i;
            earliest_order_hint = hint;
        }
        return ref;
    }

    private boolean setFrameReferences(int lastFrameIdx, int goldFrameIdx) {
        int[] kRefFrameList;
        for (int reference_frame_index : this.frame_header_.reference_frame_index) {
            reference_frame_index = -1;
        }
        this.frame_header_.reference_frame_index[0] = lastFrameIdx;
        this.frame_header_.reference_frame_index[3] = goldFrameIdx;
        boolean[] usedFrame = new boolean[8];
        usedFrame[lastFrameIdx] = true;
        usedFrame[goldFrameIdx] = true;
        int current_frame_hint = 1 << this.sequence_header_.order_hint_bits - 1;
        int[] shifted_order_hints = new int[8];
        for (int i = 0; i < 8; ++i) {
            int relative_distance = D.GetRelativeDistance(this.decoder_state_.reference_order_hint[i], this.frame_header_.order_hint, this.sequence_header_.order_hint_shift_bits);
            shifted_order_hints[i] = current_frame_hint + relative_distance;
        }
        int last_order_hint = shifted_order_hints[lastFrameIdx];
        int gold_order_hint = shifted_order_hints[goldFrameIdx];
        if (last_order_hint >= current_frame_hint || gold_order_hint >= current_frame_hint) {
            return false;
        }
        int ref = Obu.findLatestBackwardReference(current_frame_hint, shifted_order_hints, usedFrame);
        if (ref >= 0) {
            this.frame_header_.reference_frame_index[6] = ref;
            usedFrame[ref] = true;
        }
        if ((ref = Obu.findEarliestBackwardReference(current_frame_hint, shifted_order_hints, usedFrame)) >= 0) {
            this.frame_header_.reference_frame_index[4] = ref;
            usedFrame[ref] = true;
        }
        if ((ref = Obu.findEarliestBackwardReference(current_frame_hint, shifted_order_hints, usedFrame)) >= 0) {
            this.frame_header_.reference_frame_index[5] = ref;
            usedFrame[ref] = true;
        }
        for (int ref_frame : kRefFrameList = new int[]{2, 3, 5, 6, 7}) {
            if (this.frame_header_.reference_frame_index[ref_frame - 1] >= 0 || (ref = Obu.findLatestForwardReference(current_frame_hint, shifted_order_hints, usedFrame)) < 0) continue;
            this.frame_header_.reference_frame_index[ref_frame - 1] = ref;
            usedFrame[ref] = true;
        }
        ref = Obu.findReferenceWithSmallestOutputOrder(shifted_order_hints);
        for (int reference_frame_index : this.frame_header_.reference_frame_index) {
            if (reference_frame_index >= 0) continue;
            reference_frame_index = ref;
        }
        return true;
    }

    private boolean parseLoopFilterParameters() {
        int i;
        D.LoopFilter loopFilter = this.frame_header_.loop_filter;
        if (this.frame_header_.coded_lossless || this.frame_header_.allow_intrabc) {
            Obu.setDefaultRefDeltas(loopFilter);
            return true;
        }
        if (this.frame_header_.primary_reference_frame == 7) {
            Obu.setDefaultRefDeltas(loopFilter);
        } else {
            int prevFrameIndex = this.frame_header_.reference_frame_index[this.frame_header_.primary_reference_frame];
            D.RefCountedBuffer prev_frame = this.decoder_state_.reference_frame[prevFrameIndex];
            loopFilter.ref_deltas = prev_frame.loop_filter_ref_deltas_;
            loopFilter.mode_deltas = prev_frame.loop_filter_mode_deltas_;
        }
        for (i = 0; i < 2; ++i) {
            long scratch = this.bitReader.readLiteral(6);
            loopFilter.level[i] = (int)scratch;
        }
        if (!(this.sequence_header_.color_config.is_monochrome || loopFilter.level[0] == 0 && loopFilter.level[1] == 0)) {
            for (i = 2; i < 4; ++i) {
                long scratch = this.bitReader.readLiteral(6);
                loopFilter.level[i] = (int)scratch;
            }
        }
        long scratch = this.bitReader.readLiteral(3);
        loopFilter.sharpness = (int)scratch;
        scratch = this.bitReader.readBit();
        boolean bl = loopFilter.delta_enabled = scratch != 0L;
        if (loopFilter.delta_enabled) {
            scratch = this.bitReader.readBit();
            boolean bl2 = loopFilter.delta_update = scratch != 0L;
            if (loopFilter.delta_update) {
                int scratch_int;
                for (int ref_delta : loopFilter.ref_deltas) {
                    boolean update_ref_delta;
                    scratch = this.bitReader.readBit();
                    boolean bl3 = update_ref_delta = scratch != 0L;
                    if (!update_ref_delta) continue;
                    scratch_int = this.bitReader.readInverseSignedLiteral(6);
                    if (scratch_int == 0) {
                        return false;
                    }
                    ref_delta = scratch_int;
                }
                for (int mode_delta : loopFilter.mode_deltas) {
                    boolean update_mode_delta;
                    scratch = this.bitReader.readBit();
                    boolean bl4 = update_mode_delta = scratch != 0L;
                    if (!update_mode_delta) continue;
                    scratch_int = this.bitReader.readInverseSignedLiteral(6);
                    if (scratch_int == 0) {
                        return false;
                    }
                    mode_delta = scratch_int;
                }
            }
        } else {
            loopFilter.delta_update = false;
        }
        return true;
    }

    private boolean parseDeltaQuantizer(int[] delta, int offset) {
        boolean delta_coded;
        delta[offset] = 0;
        long scratch = this.bitReader.readBit();
        boolean bl = delta_coded = scratch != 0L;
        if (delta_coded) {
            int scratch_int = this.bitReader.readInverseSignedLiteral(6);
            if (scratch_int == 0) {
                return false;
            }
            delta[offset] = scratch_int;
        }
        return true;
    }

    boolean parseQuantizerParameters() {
        D.QuantizerParameters quantizer = this.frame_header_.quantizer;
        long scratch = this.bitReader.readLiteral(8);
        quantizer.base_index = (int)scratch;
        if (!this.parseDeltaQuantizer(quantizer.delta_dc, 0)) {
            return false;
        }
        if (!this.sequence_header_.color_config.is_monochrome) {
            boolean diff_uv_delta = false;
            if (this.sequence_header_.color_config.separate_uv_delta_q) {
                scratch = this.bitReader.readBit();
                boolean bl = diff_uv_delta = scratch != 0L;
            }
            if (!this.parseDeltaQuantizer(quantizer.delta_dc, 1) || !this.parseDeltaQuantizer(quantizer.delta_ac, 1)) {
                return false;
            }
            if (diff_uv_delta) {
                if (!this.parseDeltaQuantizer(quantizer.delta_dc, 2) || !this.parseDeltaQuantizer(quantizer.delta_ac, 2)) {
                    return false;
                }
            } else {
                quantizer.delta_dc[2] = quantizer.delta_dc[1];
                quantizer.delta_ac[2] = quantizer.delta_ac[1];
            }
        }
        boolean bl = quantizer.use_matrix = (scratch = (long)this.bitReader.readBit()) != 0L;
        if (quantizer.use_matrix) {
            scratch = this.bitReader.readLiteral(4);
            quantizer.matrix_level[0] = (int)scratch;
            scratch = this.bitReader.readLiteral(4);
            quantizer.matrix_level[1] = (int)scratch;
            if (this.sequence_header_.color_config.separate_uv_delta_q) {
                scratch = this.bitReader.readLiteral(4);
                quantizer.matrix_level[2] = (int)scratch;
            } else {
                quantizer.matrix_level[2] = quantizer.matrix_level[1];
            }
        }
        return true;
    }

    boolean parseSegmentationParameters() {
        D.Segmentation segmentation = this.frame_header_.segmentation;
        long scratch = this.bitReader.readBit();
        boolean bl = segmentation.enabled = scratch != 0L;
        if (!segmentation.enabled) {
            return true;
        }
        if (this.frame_header_.primary_reference_frame == 7) {
            segmentation.update_map = true;
            segmentation.update_data = true;
        } else {
            scratch = this.bitReader.readBit();
            boolean bl2 = segmentation.update_map = scratch != 0L;
            if (segmentation.update_map) {
                scratch = this.bitReader.readBit();
                segmentation.temporal_update = scratch != 0L;
            }
            boolean bl3 = segmentation.update_data = (scratch = (long)this.bitReader.readBit()) != 0L;
            if (!segmentation.update_data) {
                int prev_frame_index = this.frame_header_.reference_frame_index[this.frame_header_.primary_reference_frame];
                this.decoder_state_.reference_frame[prev_frame_index].GetSegmentationParameters(segmentation);
                return true;
            }
        }
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) {
                scratch = this.bitReader.readBit();
                boolean bl4 = segmentation.feature_enabled[i][j] = scratch != 0L;
                if (!segmentation.feature_enabled[i][j]) continue;
                if (D.Segmentation.FeatureSigned(j)) {
                    int scratch_int = this.bitReader.readInverseSignedLiteral(D.kSegmentationFeatureBits[j]);
                    if (scratch_int == 0) {
                        return false;
                    }
                    segmentation.feature_data[i][j] = D.Clip3(scratch_int, D.kSegmentationFeatureMaxValues[j], D.kSegmentationFeatureMaxValues[j]);
                } else if (D.kSegmentationFeatureBits[j] > 0) {
                    scratch = this.bitReader.readLiteral(D.kSegmentationFeatureBits[j]);
                    segmentation.feature_data[i][j] = D.Clip3((int)scratch, 0, D.kSegmentationFeatureMaxValues[j]);
                } else {
                    segmentation.feature_data[i][j] = 0;
                }
                segmentation.last_active_segment_id = i;
                if (j < 5) continue;
                segmentation.segment_id_pre_skip = true;
            }
        }
        return true;
    }

    boolean parseQuantizerIndexDeltaParameters() {
        if (this.frame_header_.quantizer.base_index > 0) {
            long scratch = this.bitReader.readBit();
            boolean bl = this.frame_header_.delta_q.present = scratch != 0L;
            if (this.frame_header_.delta_q.present) {
                scratch = this.bitReader.readLiteral(2);
                this.frame_header_.delta_q.scale = (int)scratch;
            }
        }
        return true;
    }

    boolean parseLoopFilterDeltaParameters() {
        if (this.frame_header_.delta_q.present) {
            long scratch;
            if (!this.frame_header_.allow_intrabc) {
                scratch = this.bitReader.readBit();
                boolean bl = this.frame_header_.delta_lf.present = scratch != 0L;
            }
            if (this.frame_header_.delta_lf.present) {
                scratch = this.bitReader.readLiteral(2);
                this.frame_header_.delta_lf.scale = (int)scratch;
                scratch = this.bitReader.readBit();
                this.frame_header_.delta_lf.multi = scratch != 0L;
            }
        }
        return true;
    }

    private void computeSegmentLosslessAndQIndex() {
        this.frame_header_.coded_lossless = true;
        D.Segmentation segmentation = this.frame_header_.segmentation;
        D.QuantizerParameters quantizer = this.frame_header_.quantizer;
        for (int i = 0; i < 8; ++i) {
            segmentation.qindex[i] = Quant.GetQIndex(segmentation, i, quantizer.base_index);
            boolean bl = segmentation.lossless[i] = segmentation.qindex[i] == 0 && quantizer.delta_dc[0] == 0 && quantizer.delta_dc[1] == 0 && quantizer.delta_ac[1] == 0 && quantizer.delta_dc[2] == 0 && quantizer.delta_ac[2] == 0;
            if (segmentation.lossless[i]) continue;
            this.frame_header_.coded_lossless = false;
        }
        this.frame_header_.upscaled_lossless = this.frame_header_.coded_lossless && this.frame_header_.width == this.frame_header_.upscaled_width;
    }

    private boolean parseCdefParameters() {
        int coeff_shift = this.sequence_header_.color_config.bitdepth - 8;
        if (this.frame_header_.coded_lossless || this.frame_header_.allow_intrabc || !this.sequence_header_.enable_cdef) {
            this.frame_header_.cdef.damping = 3 + coeff_shift;
            return true;
        }
        D.Cdef cdef = this.frame_header_.cdef;
        long scratch = this.bitReader.readLiteral(2);
        cdef.damping = (int)(scratch + 3L + (long)coeff_shift);
        scratch = this.bitReader.readLiteral(2);
        cdef.bits = (int)scratch;
        for (int i = 0; i < 1 << cdef.bits; ++i) {
            scratch = this.bitReader.readLiteral(4);
            cdef.y_primary_strength[i] = (int)(scratch << coeff_shift);
            scratch = this.bitReader.readLiteral(2);
            cdef.y_secondary_strength[i] = (int)scratch;
            if (cdef.y_secondary_strength[i] == 3) {
                int n = i;
                cdef.y_secondary_strength[n] = cdef.y_secondary_strength[n] + 1;
            }
            int n = i;
            cdef.y_secondary_strength[n] = cdef.y_secondary_strength[n] << coeff_shift;
            if (this.sequence_header_.color_config.is_monochrome) continue;
            scratch = this.bitReader.readLiteral(4);
            cdef.uv_primary_strength[i] = (int)(scratch << coeff_shift);
            scratch = this.bitReader.readLiteral(2);
            cdef.uv_secondary_strength[i] = (int)scratch;
            if (cdef.uv_secondary_strength[i] == 3) {
                int n2 = i;
                cdef.uv_secondary_strength[n2] = cdef.uv_secondary_strength[n2] + 1;
            }
            int n3 = i;
            cdef.uv_secondary_strength[n3] = cdef.uv_secondary_strength[n3] << coeff_shift;
        }
        return true;
    }

    private boolean parseLoopRestorationParameters() {
        long scratch;
        if (this.frame_header_.upscaled_lossless || this.frame_header_.allow_intrabc || !this.sequence_header_.enable_restoration) {
            return true;
        }
        boolean uses_loop_restoration = false;
        boolean uses_chroma_loop_restoration = false;
        D.LoopRestoration loop_restoration = this.frame_header_.loop_restoration;
        int num_planes = this.sequence_header_.color_config.is_monochrome ? 1 : 3;
        for (int i = 0; i < num_planes; ++i) {
            scratch = this.bitReader.readLiteral(2);
            loop_restoration.type[i] = (int)scratch;
            if (loop_restoration.type[i] == 0) continue;
            uses_loop_restoration = true;
            if (i <= 0) continue;
            uses_chroma_loop_restoration = true;
        }
        if (uses_loop_restoration) {
            int unit_shift;
            if (this.sequence_header_.use_128x128_superblock) {
                scratch = this.bitReader.readBit();
                unit_shift = (int)(scratch + 1L);
            } else {
                scratch = this.bitReader.readBit();
                unit_shift = (int)scratch;
                if (unit_shift != 0) {
                    scratch = this.bitReader.readBit();
                    int unitExtraShift = (int)scratch;
                    unit_shift += unitExtraShift;
                }
            }
            loop_restoration.unit_size_log2[0] = 6 + unit_shift;
            int uv_shift = 0;
            if (this.sequence_header_.color_config.subsampling_x != 0 && this.sequence_header_.color_config.subsampling_y != 0 && uses_chroma_loop_restoration) {
                scratch = this.bitReader.readBit();
                uv_shift = (int)scratch;
            }
            loop_restoration.unit_size_log2[1] = loop_restoration.unit_size_log2[2] = loop_restoration.unit_size_log2[0] - uv_shift;
        }
        return true;
    }

    private boolean parseTxModeSyntax() {
        if (this.frame_header_.coded_lossless) {
            this.frame_header_.tx_mode = 0;
            return true;
        }
        long scratch = this.bitReader.readBit();
        this.frame_header_.tx_mode = scratch == 1L ? 2 : 1;
        return true;
    }

    private boolean parseFrameReferenceModeSyntax() {
        if (!D.IsIntraFrame(this.frame_header_.frame_type)) {
            long scratch = this.bitReader.readBit();
            this.frame_header_.reference_mode_select = scratch != 0L;
        }
        return true;
    }

    private boolean isSkipModeAllowed() {
        if (D.IsIntraFrame(this.frame_header_.frame_type) || !this.frame_header_.reference_mode_select || !this.sequence_header_.enable_order_hint) {
            return false;
        }
        int forward_index = -1;
        int backward_index = -1;
        int forward_hint = -1;
        int backward_hint = -1;
        for (int i = 0; i < 7; ++i) {
            int reference_hint = this.decoder_state_.reference_order_hint[this.frame_header_.reference_frame_index[i]];
            int relative_distance = D.GetRelativeDistance(reference_hint, this.frame_header_.order_hint, this.sequence_header_.order_hint_shift_bits);
            if (relative_distance < 0) {
                if (forward_index >= 0 && D.GetRelativeDistance(reference_hint, forward_hint, this.sequence_header_.order_hint_shift_bits) <= 0) continue;
                forward_index = i;
                forward_hint = reference_hint;
                continue;
            }
            if (relative_distance <= 0 || backward_index >= 0 && D.GetRelativeDistance(reference_hint, backward_hint, this.sequence_header_.order_hint_shift_bits) >= 0) continue;
            backward_index = i;
            backward_hint = reference_hint;
        }
        if (forward_index < 0) {
            return false;
        }
        if (backward_index >= 0) {
            this.frame_header_.skip_mode_frame[0] = 1 + Math.min(forward_index, backward_index);
            this.frame_header_.skip_mode_frame[1] = 1 + Math.max(forward_index, backward_index);
            return true;
        }
        int second_forward_index = -1;
        int second_forward_hint = -1;
        for (int i = 0; i < 7; ++i) {
            int reference_hint = this.decoder_state_.reference_order_hint[this.frame_header_.reference_frame_index[i]];
            if (D.GetRelativeDistance(reference_hint, forward_hint, this.sequence_header_.order_hint_shift_bits) >= 0 || second_forward_index >= 0 && D.GetRelativeDistance(reference_hint, second_forward_hint, this.sequence_header_.order_hint_shift_bits) <= 0) continue;
            second_forward_index = i;
            second_forward_hint = reference_hint;
        }
        if (second_forward_index < 0) {
            return false;
        }
        this.frame_header_.skip_mode_frame[0] = 1 + Math.min(forward_index, second_forward_index);
        this.frame_header_.skip_mode_frame[1] = 1 + Math.max(forward_index, second_forward_index);
        return true;
    }

    private boolean parseSkipModeParameters() {
        if (!this.isSkipModeAllowed()) {
            return true;
        }
        long scratch = this.bitReader.readBit();
        this.frame_header_.skip_mode_present = scratch != 0L;
        return true;
    }

    private boolean parseGlobalParamSyntax(int ref, int index, D.GlobalMotion[] prev_global_motions) {
        D.GlobalMotion globalMotion = this.frame_header_.global_motion[ref];
        D.GlobalMotion prevGlobalMotion = prev_global_motions[ref];
        int absBits = 12;
        int precision_bits = 15;
        if (index < 2) {
            if (globalMotion.type == 1) {
                int high_precision_mv_factor = !this.frame_header_.allow_high_precision_mv ? 1 : 0;
                absBits = 9 - high_precision_mv_factor;
                precision_bits = 3 - high_precision_mv_factor;
            } else {
                absBits = 12;
                precision_bits = 6;
            }
        }
        int precision_diff = 16 - precision_bits;
        int round = index % 3 == 2 ? 65536 : 0;
        int mx = 1 << absBits;
        int sub = index % 3 == 2 ? 1 << precision_bits : 0;
        int reference = (prevGlobalMotion.params[index] >> precision_diff) - sub;
        int scratch = this.bitReader.decodeSignedSubexpWithReference(-mx, mx + 1, reference, 3);
        if (scratch == 0) {
            return false;
        }
        globalMotion.params[index] = (scratch << precision_diff) + round;
        return true;
    }

    private boolean parseGlobalMotionParameters() {
        for (int ref = 1; ref <= 7; ++ref) {
            if (this.frame_header_.global_motion[ref] == null) {
                this.frame_header_.global_motion[ref] = new D.GlobalMotion();
            }
            this.frame_header_.global_motion[ref].type = 0;
            for (int i = 0; i < 6; ++i) {
                this.frame_header_.global_motion[ref].params[i] = i % 3 == 2 ? 65536 : 0;
            }
        }
        if (D.IsIntraFrame(this.frame_header_.frame_type)) {
            return true;
        }
        D.GlobalMotion[] prev_global_motions = new D.GlobalMotion[8];
        if (this.frame_header_.primary_reference_frame == 7) {
            prev_global_motions = this.frame_header_.global_motion;
        } else {
            int prev_frame_index = this.frame_header_.reference_frame_index[this.frame_header_.primary_reference_frame];
            prev_global_motions = this.decoder_state_.reference_frame[prev_frame_index].GlobalMotions();
        }
        for (int ref = 1; ref <= 7; ++ref) {
            boolean isGlobal;
            D.GlobalMotion global_motion = this.frame_header_.global_motion[ref];
            long scratch = this.bitReader.readBit();
            boolean bl = isGlobal = scratch != 0L;
            if (isGlobal) {
                boolean is_rot_zoom;
                scratch = this.bitReader.readBit();
                boolean bl2 = is_rot_zoom = scratch != 0L;
                if (is_rot_zoom) {
                    global_motion.type = 2;
                } else {
                    scratch = this.bitReader.readBit();
                    boolean is_translation = scratch != 0L;
                    global_motion.type = is_translation ? 1 : 3;
                }
            } else {
                global_motion.type = 0;
            }
            if (global_motion.type >= 2) {
                if (!this.parseGlobalParamSyntax(ref, 2, prev_global_motions) || !this.parseGlobalParamSyntax(ref, 3, prev_global_motions)) {
                    return false;
                }
                if (global_motion.type == 3) {
                    if (!this.parseGlobalParamSyntax(ref, 4, prev_global_motions) || !this.parseGlobalParamSyntax(ref, 5, prev_global_motions)) {
                        return false;
                    }
                } else {
                    global_motion.params[4] = -global_motion.params[3];
                    global_motion.params[5] = global_motion.params[2];
                }
            }
            if (global_motion.type < 1 || this.parseGlobalParamSyntax(ref, 0, prev_global_motions) && this.parseGlobalParamSyntax(ref, 1, prev_global_motions)) continue;
            return false;
        }
        return true;
    }

    private boolean parseFilmGrainParameters() {
        int i;
        int num_pos_y;
        int i2;
        if (!this.sequence_header_.film_grain_params_present || !this.frame_header_.show_frame && !this.frame_header_.showable_frame) {
            return true;
        }
        D.FilmGrainParams film_grain_params = this.frame_header_.film_grain_params;
        long scratch = this.bitReader.readBit();
        boolean bl = film_grain_params.apply_grain = scratch != 0L;
        if (!film_grain_params.apply_grain) {
            return true;
        }
        scratch = this.bitReader.readLiteral(16);
        film_grain_params.grain_seed = (int)scratch;
        film_grain_params.update_grain = true;
        if (this.frame_header_.frame_type == 1) {
            scratch = this.bitReader.readBit();
            boolean bl2 = film_grain_params.update_grain = scratch != 0L;
        }
        if (!film_grain_params.update_grain) {
            scratch = this.bitReader.readLiteral(3);
            film_grain_params.reference_index = (int)scratch;
            boolean found = false;
            for (int index : this.frame_header_.reference_frame_index) {
                if (film_grain_params.reference_index != index) continue;
                found = true;
                break;
            }
            if (!found) {
                return false;
            }
            D.RefCountedBuffer grain_params_reference_frame = this.decoder_state_.reference_frame[film_grain_params.reference_index];
            if (grain_params_reference_frame == null) {
                return false;
            }
            int temp_grain_seed = film_grain_params.grain_seed;
            boolean temp_update_grain = film_grain_params.update_grain;
            int temp_reference_index = film_grain_params.reference_index;
            film_grain_params = grain_params_reference_frame.film_grain_params();
            film_grain_params.grain_seed = temp_grain_seed;
            film_grain_params.update_grain = temp_update_grain;
            film_grain_params.reference_index = temp_reference_index;
            return true;
        }
        scratch = this.bitReader.readLiteral(4);
        film_grain_params.num_y_points = (int)scratch;
        if (film_grain_params.num_y_points > 14) {
            return false;
        }
        for (i2 = 0; i2 < film_grain_params.num_y_points; ++i2) {
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.point_y_value[i2] = (int)scratch;
            if (i2 != 0 && film_grain_params.point_y_value[i2 - 1] >= film_grain_params.point_y_value[i2]) {
                return false;
            }
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.point_y_scaling[i2] = (int)scratch;
        }
        if (this.sequence_header_.color_config.is_monochrome) {
            film_grain_params.chroma_scaling_from_luma = false;
        } else {
            scratch = this.bitReader.readBit();
            boolean bl3 = film_grain_params.chroma_scaling_from_luma = scratch != 0L;
        }
        if (this.sequence_header_.color_config.is_monochrome || film_grain_params.chroma_scaling_from_luma || this.sequence_header_.color_config.subsampling_x == 1 && this.sequence_header_.color_config.subsampling_y == 1 && film_grain_params.num_y_points == 0) {
            film_grain_params.num_u_points = 0;
            film_grain_params.num_v_points = 0;
        } else {
            scratch = this.bitReader.readLiteral(4);
            film_grain_params.num_u_points = (int)scratch;
            if (film_grain_params.num_u_points > 10) {
                return false;
            }
            for (i2 = 0; i2 < film_grain_params.num_u_points; ++i2) {
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.point_u_value[i2] = (int)scratch;
                if (i2 != 0 && film_grain_params.point_u_value[i2 - 1] >= film_grain_params.point_u_value[i2]) {
                    return false;
                }
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.point_u_scaling[i2] = (int)scratch;
            }
            scratch = this.bitReader.readLiteral(4);
            film_grain_params.num_v_points = (int)scratch;
            if (film_grain_params.num_v_points > 10) {
                return false;
            }
            if (this.sequence_header_.color_config.subsampling_x == 1 && this.sequence_header_.color_config.subsampling_y == 1 && film_grain_params.num_u_points == 0 != (film_grain_params.num_v_points == 0)) {
                return false;
            }
            for (i2 = 0; i2 < film_grain_params.num_v_points; ++i2) {
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.point_v_value[i2] = (int)scratch;
                if (i2 != 0 && film_grain_params.point_v_value[i2 - 1] >= film_grain_params.point_v_value[i2]) {
                    return false;
                }
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.point_v_scaling[i2] = (int)scratch;
            }
        }
        scratch = this.bitReader.readLiteral(2);
        film_grain_params.chroma_scaling = (int)(scratch + 8L);
        scratch = this.bitReader.readLiteral(2);
        film_grain_params.auto_regression_coeff_lag = (int)scratch;
        int num_pos_uv = num_pos_y = film_grain_params.auto_regression_coeff_lag * 2 * (film_grain_params.auto_regression_coeff_lag + 1);
        if (film_grain_params.num_y_points > 0) {
            ++num_pos_uv;
            for (i = 0; i < num_pos_y; ++i) {
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.auto_regression_coeff_y[i] = (int)(scratch - 128L);
            }
        }
        if (film_grain_params.chroma_scaling_from_luma || film_grain_params.num_u_points > 0) {
            for (i = 0; i < num_pos_uv; ++i) {
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.auto_regression_coeff_u[i] = (int)(scratch - 128L);
            }
        }
        if (film_grain_params.chroma_scaling_from_luma || film_grain_params.num_v_points > 0) {
            for (i = 0; i < num_pos_uv; ++i) {
                scratch = this.bitReader.readLiteral(8);
                film_grain_params.auto_regression_coeff_v[i] = (int)(scratch - 128L);
            }
        }
        scratch = this.bitReader.readLiteral(2);
        film_grain_params.auto_regression_shift = (int)(scratch + 6L);
        scratch = this.bitReader.readLiteral(2);
        film_grain_params.grain_scale_shift = (int)scratch;
        if (film_grain_params.num_u_points > 0) {
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.u_multiplier = (int)(scratch - 128L);
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.u_luma_multiplier = (int)(scratch - 128L);
            scratch = this.bitReader.readLiteral(9);
            film_grain_params.u_offset = (int)(scratch - 256L);
        }
        if (film_grain_params.num_v_points > 0) {
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.v_multiplier = (int)(scratch - 128L);
            scratch = this.bitReader.readLiteral(8);
            film_grain_params.v_luma_multiplier = (int)(scratch - 128L);
            scratch = this.bitReader.readLiteral(9);
            film_grain_params.v_offset = (int)(scratch - 256L);
        }
        film_grain_params.overlap_flag = (scratch = (long)this.bitReader.readBit()) != 0L;
        scratch = this.bitReader.readBit();
        film_grain_params.clip_to_restricted_range = scratch != 0L;
        return true;
    }

    private boolean parseTileInfoSyntax() {
        D.TileInfo tile_info = this.frame_header_.tile_info;
        int sb_columns = this.sequence_header_.use_128x128_superblock ? this.frame_header_.columns4x4 + 31 >> 5 : this.frame_header_.columns4x4 + 15 >> 4;
        int sb_rows = this.sequence_header_.use_128x128_superblock ? this.frame_header_.rows4x4 + 31 >> 5 : this.frame_header_.rows4x4 + 15 >> 4;
        tile_info.sb_columns = sb_columns;
        tile_info.sb_rows = sb_rows;
        int sb_shift = this.sequence_header_.use_128x128_superblock ? 5 : 4;
        int sb_size = 2 + sb_shift;
        int sb_max_tile_width = 4096 >> sb_size;
        int sb_max_tile_area = 0x900000 >> sb_size * 2;
        int minlog2_tile_columns = Obu.tileLog2(sb_max_tile_width, sb_columns);
        int maxlog2_tile_columns = D.CeilLog2(Math.min(sb_columns, 64));
        int maxlog2_tile_rows = D.CeilLog2(Math.min(sb_rows, 64));
        int min_log2_tiles = Math.max(minlog2_tile_columns, Obu.tileLog2(sb_max_tile_area, sb_rows * sb_columns));
        long scratch = this.bitReader.readBit();
        boolean bl = tile_info.uniform_spacing = scratch != 0L;
        if (tile_info.uniform_spacing) {
            int minlog2_tile_rows;
            tile_info.tile_columns_log2 = minlog2_tile_columns;
            while (tile_info.tile_columns_log2 < maxlog2_tile_columns && (scratch = (long)this.bitReader.readBit()) != 0L) {
                ++tile_info.tile_columns_log2;
            }
            int sb_tile_width = sb_columns + (1 << tile_info.tile_columns_log2) - 1 >> tile_info.tile_columns_log2;
            if (sb_tile_width <= 0) {
                return false;
            }
            int i = 0;
            for (int sb_start = 0; sb_start < sb_columns; sb_start += sb_tile_width) {
                if (i >= 64) {
                    return false;
                }
                tile_info.tile_column_start[i++] = sb_start << sb_shift;
            }
            tile_info.tile_column_start[i] = this.frame_header_.columns4x4;
            tile_info.tile_columns = i;
            tile_info.tile_rows_log2 = minlog2_tile_rows = Math.max(min_log2_tiles - tile_info.tile_columns_log2, 0);
            while (tile_info.tile_rows_log2 < maxlog2_tile_rows && (scratch = (long)this.bitReader.readBit()) != 0L) {
                ++tile_info.tile_rows_log2;
            }
            int sb_tile_height = sb_rows + (1 << tile_info.tile_rows_log2) - 1 >> tile_info.tile_rows_log2;
            if (sb_tile_height <= 0) {
                return false;
            }
            i = 0;
            for (int sb_start = 0; sb_start < sb_rows; sb_start += sb_tile_height) {
                if (i >= 64) {
                    return false;
                }
                tile_info.tile_row_start[i++] = sb_start << sb_shift;
            }
            tile_info.tile_row_start[i] = this.frame_header_.rows4x4;
            tile_info.tile_rows = i;
        } else {
            int widest_tile_sb = 1;
            int i = 0;
            int sb_start = 0;
            while (sb_start < sb_columns) {
                if (i >= 64) {
                    return false;
                }
                tile_info.tile_column_start[i] = sb_start << sb_shift;
                int max_width = Math.min(sb_columns - sb_start, sb_max_tile_width);
                tile_info.tile_column_width_in_superblocks[i] = this.bitReader.decodeUniform(max_width);
                if (tile_info.tile_column_width_in_superblocks[i] == 0) {
                    LogWriter.writeLog("OBU: parse tile info Not enough bits.");
                    return false;
                }
                int n = i;
                tile_info.tile_column_width_in_superblocks[n] = tile_info.tile_column_width_in_superblocks[n] + 1;
                widest_tile_sb = Math.max(tile_info.tile_column_width_in_superblocks[i], widest_tile_sb);
                sb_start += tile_info.tile_column_width_in_superblocks[i];
                ++i;
            }
            tile_info.tile_column_start[i] = this.frame_header_.columns4x4;
            tile_info.tile_columns = i;
            tile_info.tile_columns_log2 = D.CeilLog2(tile_info.tile_columns);
            int max_tile_area_sb = sb_rows * sb_columns;
            if (min_log2_tiles > 0) {
                max_tile_area_sb >>= min_log2_tiles + 1;
            }
            int max_tile_height_sb = Math.max(max_tile_area_sb / widest_tile_sb, 1);
            i = 0;
            int sb_start2 = 0;
            while (sb_start2 < sb_rows) {
                if (i >= 64) {
                    return false;
                }
                tile_info.tile_row_start[i] = sb_start2 << sb_shift;
                int max_height = Math.min(sb_rows - sb_start2, max_tile_height_sb);
                tile_info.tile_row_height_in_superblocks[i] = this.bitReader.decodeUniform(max_height);
                if (tile_info.tile_row_height_in_superblocks[i] == 0) {
                    LogWriter.writeLog("OBU: parse tile info Not enough bits.");
                    return false;
                }
                int n = i;
                tile_info.tile_row_height_in_superblocks[n] = tile_info.tile_row_height_in_superblocks[n] + 1;
                sb_start2 += tile_info.tile_row_height_in_superblocks[i];
                ++i;
            }
            tile_info.tile_row_start[i] = this.frame_header_.rows4x4;
            tile_info.tile_rows = i;
            tile_info.tile_rows_log2 = D.CeilLog2(tile_info.tile_rows);
        }
        tile_info.tile_count = tile_info.tile_rows * tile_info.tile_columns;
        tile_info.context_update_id = 0;
        int tile_bits = tile_info.tile_columns_log2 + tile_info.tile_rows_log2;
        if (tile_bits != 0) {
            scratch = this.bitReader.readLiteral(tile_bits);
            tile_info.context_update_id = (int)scratch;
            if (tile_info.context_update_id >= tile_info.tile_count) {
                return false;
            }
            scratch = this.bitReader.readLiteral(2);
            tile_info.tile_size_bytes = (int)(1L + scratch);
        }
        return true;
    }

    private boolean readAllowWarpedMotion() {
        if (D.IsIntraFrame(this.frame_header_.frame_type) || this.frame_header_.error_resilient_mode || !this.sequence_header_.enable_warped_motion) {
            return true;
        }
        long scratch = this.bitReader.readBit();
        this.frame_header_.allow_warped_motion = scratch != 0L;
        return true;
    }

    private boolean parseFrameParameters() {
        int i;
        long scratch;
        if (this.sequence_header_.reduced_still_picture_header) {
            this.frame_header_.show_frame = true;
            if (!this.ensureCurrentFrameIsNotNull()) {
                return false;
            }
        } else {
            scratch = this.bitReader.readBit();
            boolean bl = this.frame_header_.show_existing_frame = scratch != 0L;
            if (this.frame_header_.show_existing_frame) {
                scratch = this.bitReader.readLiteral(3);
                this.frame_header_.frame_to_show = (int)scratch;
                if (this.sequence_header_.decoder_model_info_present_flag && !this.sequence_header_.timing_info.equal_picture_interval) {
                    scratch = this.bitReader.readLiteral(this.sequence_header_.decoder_model_info.frame_presentation_time_length);
                    this.frame_header_.frame_presentation_time = (int)scratch;
                }
                if (this.sequence_header_.frame_id_numbers_present) {
                    scratch = this.bitReader.readLiteral(this.sequence_header_.frame_id_length_bits);
                    this.frame_header_.display_frame_id = (int)(scratch & 0xFFFFL);
                    if (this.frame_header_.display_frame_id != this.decoder_state_.reference_frame_id[this.frame_header_.frame_to_show]) {
                        LogWriter.writeLog("OBU: parse frame parameters error");
                        return false;
                    }
                }
                this.current_frame_ = this.decoder_state_.reference_frame[this.frame_header_.frame_to_show];
                if (this.current_frame_ == null) {
                    LogWriter.writeLog("OBU: parse frame parameters error null frame");
                    return false;
                }
                if (!this.current_frame_.showable_frame()) {
                    LogWriter.writeLog("OBU: parse frame parameters error no show frame");
                    return false;
                }
                if (this.current_frame_.frame_type() == 0) {
                    this.frame_header_.refresh_frame_flags = 255;
                    this.current_frame_.set_showable_frame(false);
                    this.decoder_state_.current_frame_id = this.decoder_state_.reference_frame_id[this.frame_header_.frame_to_show];
                    this.decoder_state_.order_hint = this.decoder_state_.reference_order_hint[this.frame_header_.frame_to_show];
                }
                return true;
            }
            if (!this.ensureCurrentFrameIsNotNull()) {
                return false;
            }
            scratch = this.bitReader.readLiteral(2);
            this.frame_header_.frame_type = (int)scratch;
            this.current_frame_.set_frame_type(this.frame_header_.frame_type);
            scratch = this.bitReader.readBit();
            boolean bl2 = this.frame_header_.show_frame = scratch != 0L;
            if (this.frame_header_.show_frame && this.sequence_header_.decoder_model_info_present_flag && !this.sequence_header_.timing_info.equal_picture_interval) {
                scratch = this.bitReader.readLiteral(this.sequence_header_.decoder_model_info.frame_presentation_time_length);
                this.frame_header_.frame_presentation_time = (int)scratch;
            }
            this.frame_header_.showable_frame = this.frame_header_.show_frame ? this.frame_header_.frame_type != 0 : (scratch = (long)this.bitReader.readBit()) != 0L;
            this.current_frame_.set_showable_frame(this.frame_header_.showable_frame);
            if (this.frame_header_.frame_type == 3 || this.frame_header_.frame_type == 0 && this.frame_header_.show_frame) {
                this.frame_header_.error_resilient_mode = true;
            } else {
                this.bitReader.readBit();
                boolean bl3 = this.frame_header_.error_resilient_mode = scratch != 0L;
            }
        }
        if (this.frame_header_.frame_type == 0 && this.frame_header_.show_frame) {
            Arrays.fill(this.decoder_state_.reference_order_hint, 0);
            Arrays.fill(this.decoder_state_.reference_frame, null);
        }
        boolean bl = this.frame_header_.enable_cdf_update = (scratch = (long)this.bitReader.readBit()) == 0L;
        if (this.sequence_header_.force_screen_content_tools == 2) {
            scratch = this.bitReader.readBit();
            this.frame_header_.allow_screen_content_tools = scratch != 0L;
        } else {
            boolean bl4 = this.frame_header_.allow_screen_content_tools = this.sequence_header_.force_screen_content_tools != 0;
        }
        if (this.frame_header_.allow_screen_content_tools) {
            if (this.sequence_header_.force_integer_mv == 2) {
                scratch = this.bitReader.readBit();
                this.frame_header_.force_integer_mv = (int)scratch;
            } else {
                this.frame_header_.force_integer_mv = this.sequence_header_.force_integer_mv;
            }
        } else {
            this.frame_header_.force_integer_mv = 0;
        }
        if (D.IsIntraFrame(this.frame_header_.frame_type)) {
            this.frame_header_.force_integer_mv = 1;
        }
        if (this.sequence_header_.frame_id_numbers_present) {
            scratch = this.bitReader.readLiteral(this.sequence_header_.frame_id_length_bits);
            this.frame_header_.current_frame_id = (int)(scratch & 0xFFFFL);
            int previous_frame_id = this.decoder_state_.current_frame_id;
            this.decoder_state_.current_frame_id = this.frame_header_.current_frame_id;
            if (this.frame_header_.frame_type != 0 || !this.frame_header_.show_frame) {
                if (previous_frame_id >= 0) {
                    int diff_frame_id = this.decoder_state_.current_frame_id - previous_frame_id;
                    int id_length_max_value = 1 << this.sequence_header_.frame_id_length_bits;
                    if (diff_frame_id <= 0) {
                        diff_frame_id += id_length_max_value;
                    }
                    if (diff_frame_id >= id_length_max_value / 2) {
                        LogWriter.writeLog("OBU: parse frame parameters error frame id error");
                        return false;
                    }
                }
                this.markInvalidReferenceFrames();
            }
        } else {
            this.decoder_state_.current_frame_id = this.frame_header_.current_frame_id = 0;
        }
        if (this.frame_header_.frame_type == 3) {
            this.frame_header_.frame_size_override_flag = true;
        } else if (!this.sequence_header_.reduced_still_picture_header) {
            scratch = this.bitReader.readBit();
            boolean bl5 = this.frame_header_.frame_size_override_flag = scratch != 0L;
        }
        if (this.sequence_header_.order_hint_bits > 0) {
            scratch = this.bitReader.readLiteral(this.sequence_header_.order_hint_bits);
            this.frame_header_.order_hint = (int)scratch;
        }
        this.decoder_state_.order_hint = this.frame_header_.order_hint;
        if (D.IsIntraFrame(this.frame_header_.frame_type) || this.frame_header_.error_resilient_mode) {
            this.frame_header_.primary_reference_frame = 7;
        } else {
            scratch = this.bitReader.readLiteral(3);
            this.frame_header_.primary_reference_frame = (int)scratch;
        }
        if (this.sequence_header_.decoder_model_info_present_flag) {
            boolean buffer_removal_time_present;
            scratch = this.bitReader.readBit();
            boolean bl6 = buffer_removal_time_present = scratch != 0L;
            if (buffer_removal_time_present) {
                for (i = 0; i < this.sequence_header_.operating_points; ++i) {
                    if (!this.sequence_header_.decoder_model_present_for_operating_point[i]) continue;
                    int index = this.sequence_header_.operating_point_idc[i];
                    int last = this.obu_headers_.size() - 1;
                    if (index != 0 && (!Obu.inTemporalLayer(index, this.obu_headers_.get((int)last).temporal_id) || !Obu.inSpatialLayer(index, this.obu_headers_.get((int)last).spatial_id))) continue;
                    scratch = this.bitReader.readLiteral(this.sequence_header_.decoder_model_info.buffer_removal_time_length);
                    this.frame_header_.buffer_removal_time[i] = (int)scratch;
                }
            }
        }
        if (this.frame_header_.frame_type == 3 || this.frame_header_.frame_type == 0 && this.frame_header_.show_frame) {
            this.frame_header_.refresh_frame_flags = 255;
        } else {
            scratch = this.bitReader.readLiteral(8);
            this.frame_header_.refresh_frame_flags = (int)scratch;
            if (this.frame_header_.frame_type == 2 && this.frame_header_.refresh_frame_flags == 255) {
                LogWriter.writeLog("OBU: parse frame parameters error frame type");
                return false;
            }
        }
        if (!(D.IsIntraFrame(this.frame_header_.frame_type) && this.frame_header_.refresh_frame_flags == 255 || this.parseReferenceOrderHint())) {
            LogWriter.writeLog("OBU: parse frame parameters error intra only frame");
            return false;
        }
        if (D.IsIntraFrame(this.frame_header_.frame_type)) {
            if (!this.parseFrameSizeAndRenderSize()) {
                return false;
            }
            if (this.frame_header_.allow_screen_content_tools && this.frame_header_.width == this.frame_header_.upscaled_width) {
                scratch = this.bitReader.readBit();
                this.frame_header_.allow_intrabc = scratch != 0L;
            }
        } else {
            boolean is_filter_switchable;
            if (!this.sequence_header_.enable_order_hint) {
                this.frame_header_.frame_refs_short_signaling = false;
            } else {
                scratch = this.bitReader.readBit();
                boolean bl7 = this.frame_header_.frame_refs_short_signaling = scratch != 0L;
                if (this.frame_header_.frame_refs_short_signaling) {
                    scratch = this.bitReader.readLiteral(3);
                    int last_frame_idx = (int)scratch;
                    int gold_frame_idx = (int)(scratch = this.bitReader.readLiteral(3));
                    if (!this.setFrameReferences(last_frame_idx, gold_frame_idx)) {
                        return false;
                    }
                }
            }
            for (int i2 = 0; i2 < 7; ++i2) {
                int reference_frame_index;
                if (!this.frame_header_.frame_refs_short_signaling) {
                    scratch = this.bitReader.readLiteral(3);
                    this.frame_header_.reference_frame_index[i2] = (int)scratch;
                }
                if (this.decoder_state_.reference_frame[reference_frame_index = this.frame_header_.reference_frame_index[i2]] == null) {
                    LogWriter.writeLog("OBU: parse frame parameters error ref frame is null");
                    return false;
                }
                if (!this.sequence_header_.frame_id_numbers_present) continue;
                scratch = this.bitReader.readLiteral(this.sequence_header_.delta_frame_id_length_bits);
                int delta_frame_id = (int)(1L + scratch);
                int id_length_max_value = 1 << this.sequence_header_.frame_id_length_bits;
                this.frame_header_.expected_frame_id[i2] = (this.frame_header_.current_frame_id + id_length_max_value - delta_frame_id) % id_length_max_value;
                if (this.frame_header_.expected_frame_id[i2] == this.decoder_state_.reference_frame_id[reference_frame_index]) continue;
                LogWriter.writeLog("OBU: parse frame parameters error reference buffer");
                return false;
            }
            if (this.frame_header_.frame_size_override_flag && !this.frame_header_.error_resilient_mode) {
                for (int index : this.frame_header_.reference_frame_index) {
                    scratch = this.bitReader.readBit();
                    boolean bl8 = this.frame_header_.found_reference = scratch != 0L;
                    if (!this.frame_header_.found_reference) continue;
                    D.RefCountedBuffer reference_frame = this.decoder_state_.reference_frame[index];
                    this.frame_header_.width = reference_frame.upscaled_width();
                    this.frame_header_.height = reference_frame.frame_height();
                    this.frame_header_.render_width = reference_frame.render_width();
                    this.frame_header_.render_height = reference_frame.render_height();
                    if (this.parseSuperResParametersAndComputeImageSize()) break;
                    return false;
                }
                if (!this.frame_header_.found_reference && !this.parseFrameSizeAndRenderSize()) {
                    return false;
                }
            } else if (!this.parseFrameSizeAndRenderSize()) {
                return false;
            }
            if (!this.validateInterFrameSize()) {
                return false;
            }
            this.frame_header_.allow_high_precision_mv = this.frame_header_.force_integer_mv != 0 ? false : (scratch = (long)this.bitReader.readBit()) != 0L;
            scratch = this.bitReader.readBit();
            boolean bl9 = is_filter_switchable = scratch != 0L;
            if (is_filter_switchable) {
                this.frame_header_.interpolation_filter = 4;
            } else {
                scratch = this.bitReader.readLiteral(2);
                this.frame_header_.interpolation_filter = (int)scratch;
            }
            scratch = this.bitReader.readBit();
            boolean bl10 = this.frame_header_.is_motion_mode_switchable = scratch != 0L;
            if (this.frame_header_.error_resilient_mode || !this.sequence_header_.enable_ref_frame_mvs) {
                this.frame_header_.use_ref_frame_mvs = false;
            } else {
                scratch = this.bitReader.readBit();
                boolean bl11 = this.frame_header_.use_ref_frame_mvs = scratch != 0L;
            }
        }
        if (!this.current_frame_.SetFrameDimensions(this.frame_header_)) {
            LogWriter.writeLog("OBU: parse frame parameters error setting dimension");
            return false;
        }
        if (!D.IsIntraFrame(this.frame_header_.frame_type)) {
            D.ReferenceInfo reference_info = this.current_frame_.reference_info();
            reference_info.order_hint[0] = this.frame_header_.order_hint;
            reference_info.relative_distance_from[0] = 0;
            reference_info.relative_distance_to[0] = 0;
            reference_info.skip_references[0] = true;
            reference_info.projection_divisions[0] = 0;
            for (i = 1; i <= 7; ++i) {
                int hint;
                int reference_frame = i;
                reference_info.order_hint[reference_frame] = hint = this.decoder_state_.reference_order_hint[this.frame_header_.reference_frame_index[i - 1]];
                int relative_distance_from = D.GetRelativeDistance(hint, this.frame_header_.order_hint, this.sequence_header_.order_hint_shift_bits);
                int relative_distance_to = D.GetRelativeDistance(this.frame_header_.order_hint, hint, this.sequence_header_.order_hint_shift_bits);
                reference_info.relative_distance_from[reference_frame] = relative_distance_from;
                reference_info.relative_distance_to[reference_frame] = relative_distance_to;
                reference_info.skip_references[reference_frame] = relative_distance_to > 31 || relative_distance_to <= 0;
                reference_info.projection_divisions[reference_frame] = reference_info.skip_references[reference_frame] ? 0 : D.kProjectionMvDivisionLookup[relative_distance_to];
                this.decoder_state_.reference_frame_sign_bias[reference_frame] = relative_distance_from > 0;
            }
        }
        this.frame_header_.enable_frame_end_update_cdf = this.frame_header_.enable_cdf_update && !this.sequence_header_.reduced_still_picture_header ? (scratch = (long)this.bitReader.readBit()) == 0L : false;
        return true;
    }

    private boolean parseFrameHeader() {
        boolean status;
        if (!this.has_sequence_header_) {
            return false;
        }
        if (!this.parseFrameParameters()) {
            return false;
        }
        if (this.frame_header_.show_existing_frame) {
            return true;
        }
        int last = this.obu_headers_.size() - 1;
        this.current_frame_.set_spatial_id(this.obu_headers_.get((int)last).spatial_id);
        this.current_frame_.set_temporal_id(this.obu_headers_.get((int)last).temporal_id);
        boolean bl = status = this.parseTileInfoSyntax() && this.parseQuantizerParameters() && this.parseSegmentationParameters();
        if (!status) {
            return false;
        }
        this.current_frame_.SetSegmentationParameters(this.frame_header_.segmentation);
        boolean bl2 = status = this.parseQuantizerIndexDeltaParameters() && this.parseLoopFilterDeltaParameters();
        if (!status) {
            return false;
        }
        this.computeSegmentLosslessAndQIndex();
        if (this.frame_header_.coded_lossless && this.frame_header_.delta_q.present) {
            return false;
        }
        status = this.parseLoopFilterParameters();
        if (!status) {
            return false;
        }
        this.current_frame_.SetLoopFilterDeltas(this.frame_header_.loop_filter);
        boolean bl3 = status = this.parseCdefParameters() && this.parseLoopRestorationParameters() && this.parseTxModeSyntax() && this.parseFrameReferenceModeSyntax() && this.parseSkipModeParameters() && this.readAllowWarpedMotion();
        if (!status) {
            return false;
        }
        long scratch = this.bitReader.readBit();
        this.frame_header_.reduced_tx_set = scratch != 0L;
        status = this.parseGlobalMotionParameters();
        if (!status) {
            return false;
        }
        this.current_frame_.SetGlobalMotions(this.frame_header_.global_motion);
        status = this.parseFilmGrainParameters();
        if (!status) {
            return false;
        }
        if (this.sequence_header_.film_grain_params_present) {
            this.current_frame_.set_film_grain_params(this.frame_header_.film_grain_params);
        }
        return true;
    }

    private boolean parsePadding(byte[] data, int dataOffset, int size) {
        if (size == 0) {
            return true;
        }
        int i = Obu.getLastNonzeroByteIndex(data, dataOffset, size);
        if (i < 0) {
            return false;
        }
        if ((data[dataOffset + i] & 0xFF) != 128) {
            LogWriter.writeLog("OBU parse padding error");
            return false;
        }
        this.bitReader.skipBytes(i);
        return true;
    }

    boolean parseMetadataScalability() {
        long scratch = this.bitReader.readLiteral(8);
        int scalability_mode_idc = (int)scratch;
        if (scalability_mode_idc == 14) {
            int i;
            scratch = this.bitReader.readLiteral(2);
            int spatial_layers_count = (int)(scratch + 1L);
            scratch = this.bitReader.readBit();
            boolean spatial_layer_dimensions_present_flag = scratch != 0L;
            scratch = this.bitReader.readBit();
            boolean spatial_layer_description_present_flag = scratch != 0L;
            scratch = this.bitReader.readBit();
            boolean temporal_group_description_present_flag = scratch != 0L;
            scratch = this.bitReader.readLiteral(3);
            if (scratch != 0L) {
                LogWriter.writeLog("OBU: scalability_structure_reserved_3bits is not zero.");
            }
            if (spatial_layer_dimensions_present_flag) {
                for (i = 0; i < spatial_layers_count; ++i) {
                    scratch = this.bitReader.readLiteral(16);
                    scratch = this.bitReader.readLiteral(16);
                }
            }
            if (spatial_layer_description_present_flag) {
                for (i = 0; i < spatial_layers_count; ++i) {
                    scratch = this.bitReader.readLiteral(8);
                }
            }
            if (temporal_group_description_present_flag) {
                scratch = this.bitReader.readLiteral(8);
                int temporal_group_size = (int)scratch;
                for (int i2 = 0; i2 < temporal_group_size; ++i2) {
                    scratch = this.bitReader.readLiteral(3);
                    scratch = this.bitReader.readBit();
                    scratch = this.bitReader.readBit();
                    scratch = this.bitReader.readLiteral(3);
                    int temporal_group_ref_count = (int)scratch;
                    for (int j = 0; j < temporal_group_ref_count; ++j) {
                        scratch = this.bitReader.readLiteral(8);
                    }
                }
            }
        }
        return true;
    }

    boolean parseMetadataTimecode() {
        int time_offset_length;
        long scratch = this.bitReader.readLiteral(5);
        scratch = this.bitReader.readBit();
        boolean full_timestamp_flag = scratch != 0L;
        scratch = this.bitReader.readBit();
        scratch = this.bitReader.readBit();
        scratch = this.bitReader.readLiteral(9);
        if (full_timestamp_flag) {
            scratch = this.bitReader.readLiteral(6);
            long seconds_value = (int)scratch;
            if (seconds_value > 59L) {
                return false;
            }
            scratch = this.bitReader.readLiteral(6);
            long minutes_value = (int)scratch;
            if (minutes_value > 59L) {
                return false;
            }
            scratch = this.bitReader.readLiteral(5);
            int hours_value = (int)scratch;
            if (hours_value > 23) {
                return false;
            }
        } else {
            boolean seconds_flag;
            scratch = this.bitReader.readBit();
            boolean bl = seconds_flag = scratch != 0L;
            if (seconds_flag) {
                boolean minutes_flag;
                scratch = this.bitReader.readLiteral(6);
                int seconds_value = (int)scratch;
                if (seconds_value > 59) {
                    return false;
                }
                scratch = this.bitReader.readBit();
                boolean bl2 = minutes_flag = scratch != 0L;
                if (minutes_flag) {
                    int hours_value;
                    boolean hours_flag;
                    scratch = this.bitReader.readLiteral(6);
                    int minutes_value = (int)scratch;
                    if (minutes_value > 59) {
                        return false;
                    }
                    scratch = this.bitReader.readBit();
                    boolean bl3 = hours_flag = scratch != 0L;
                    if (hours_flag && (hours_value = (int)(scratch = this.bitReader.readLiteral(5))) > 23) {
                        return false;
                    }
                }
            }
        }
        if ((time_offset_length = (int)(scratch = this.bitReader.readLiteral(5))) > 0) {
            scratch = this.bitReader.readLiteral(time_offset_length);
        }
        return true;
    }

    boolean addTileBuffers(int start, int end, int totalSize, int tgHeaderSize, int bytesConsumedSoFar) {
        if (start != this.next_tile_group_start_ || start > end || end >= this.frame_header_.tile_info.tile_count) {
            LogWriter.writeLog("OBU: Invalid tile group start");
            return false;
        }
        this.next_tile_group_start_ = end + 1;
        if (totalSize < tgHeaderSize) {
            LogWriter.writeLog("OBU: Invalid tile total size");
            return false;
        }
        int bytes_left = totalSize - tgHeaderSize;
        byte[] data = this.data_;
        int dataOffset = bytesConsumedSoFar + tgHeaderSize;
        for (int tile_number = start; tile_number <= end; ++tile_number) {
            int tile_size = 0;
            if (tile_number != end) {
                RawBit bit_reader = new RawBit(data, dataOffset, bytes_left);
                tile_size = bit_reader.readLittleEndian(this.frame_header_.tile_info.tile_size_bytes);
                if (tile_size == 0) {
                    LogWriter.writeLog("OBU: could not read tile");
                    return false;
                }
                dataOffset += this.frame_header_.tile_info.tile_size_bytes;
                if (++tile_size > (bytes_left -= this.frame_header_.tile_info.tile_size_bytes)) {
                    LogWriter.writeLog("OBU: invalid tile size");
                    return false;
                }
            } else {
                tile_size = bytes_left;
                if (tile_size == 0) {
                    LogWriter.writeLog("OBU: invalid tile size for tile");
                    return false;
                }
            }
            D.TileBuffer tb = new D.TileBuffer(data, dataOffset, tile_size);
            this.tile_buffers_.add(tb);
            dataOffset += tile_size;
            bytes_left -= tile_size;
        }
        this.bitReader.skipBytes(totalSize - tgHeaderSize);
        return true;
    }

    private boolean parseTileGroup(int size, int bytesConsumedSoFar) {
        boolean tile_start_and_end_present_flag;
        D.TileInfo tile_info = this.frame_header_.tile_info;
        int start_offset = this.bitReader.byte_offset();
        int tile_bits = tile_info.tile_columns_log2 + tile_info.tile_rows_log2;
        if (tile_bits == 0) {
            return this.addTileBuffers(0, 0, size, 0, bytesConsumedSoFar);
        }
        long scratch = this.bitReader.readBit();
        boolean bl = tile_start_and_end_present_flag = scratch != 0L;
        if (!tile_start_and_end_present_flag) {
            if (!this.bitReader.alignToNextByte()) {
                LogWriter.writeLog("OBU: parse tile group error");
                return false;
            }
            return this.addTileBuffers(0, tile_info.tile_count - 1, size, 1, bytesConsumedSoFar);
        }
        int last = this.obu_headers_.size() - 1;
        if (this.obu_headers_.get((int)last).type == 6) {
            LogWriter.writeLog("OBU: parse tile group error");
            return false;
        }
        scratch = this.bitReader.readLiteral(tile_bits);
        int start = (int)scratch;
        scratch = this.bitReader.readLiteral(tile_bits);
        int end = (int)scratch;
        if (!this.bitReader.alignToNextByte()) {
            LogWriter.writeLog("OBU: parse tile group error");
            return false;
        }
        int tg_header_size = this.bitReader.byte_offset() - start_offset;
        return this.addTileBuffers(start, end, size, tg_header_size, bytesConsumedSoFar);
    }

    private boolean parseHeader() {
        D.ObuHeader obu_header = new D.ObuHeader();
        this.bitReader.readBit();
        long scratch = this.bitReader.readLiteral(4);
        obu_header.type = (int)scratch;
        scratch = this.bitReader.readBit();
        boolean extension_flag = scratch != 0L;
        scratch = this.bitReader.readBit();
        obu_header.has_size_field = scratch != 0L;
        scratch = this.bitReader.readBit();
        obu_header.has_extension = extension_flag;
        if (extension_flag) {
            if (this.extension_disallowed_) {
                return false;
            }
            scratch = this.bitReader.readLiteral(3);
            obu_header.temporal_id = (int)scratch;
            scratch = this.bitReader.readLiteral(2);
            obu_header.spatial_id = (int)scratch;
            scratch = this.bitReader.readLiteral(3);
        } else {
            obu_header.temporal_id = 0;
            obu_header.spatial_id = 0;
        }
        this.obu_headers_.add(obu_header);
        return true;
    }

    private void initBitReader(byte[] data, int offset, int size) {
        this.bitReader = new RawBit(data, offset, size);
    }

    boolean ensureCurrentFrameIsNotNull() {
        if (this.current_frame_ != null) {
            return true;
        }
        this.current_frame_ = this.buffer_pool_.GetFreeBuffer();
        return this.current_frame_ != null;
    }

    int parseOneFrame() {
        if (this.data_ == null || this.size_ == 0) {
            return -2;
        }
        byte[] data = this.data_;
        int totalDataLength = this.data_.length;
        int dataOffset = this.dataOffset_;
        int size = this.size_;
        this.obu_headers_.clear();
        this.frame_header_ = new D.ObuFrameHeader();
        this.tile_buffers_.clear();
        this.next_tile_group_start_ = 0;
        this.sequence_header_changed_ = false;
        boolean parsed_one_full_frame = false;
        boolean seen_frame_header = false;
        byte[] frame_header = null;
        int frame_headerPos = 0;
        int frame_header_size_in_bits = 0;
        while (size > 0 && !parsed_one_full_frame) {
            this.initBitReader(data, dataOffset, size);
            if (!this.parseHeader()) {
                LogWriter.writeLog("OBU: Failed to parse OBU Header.");
                return -9;
            }
            int last = this.obu_headers_.size() - 1;
            D.ObuHeader obu_header = this.obu_headers_.get(last);
            if (!obu_header.has_size_field) {
                return -7;
            }
            int obu_header_size = this.bitReader.byte_offset();
            int obu_size = (int)this.bitReader.readUnsignedLeb128();
            if (obu_size == 0 && obu_header.type != 2) {
                return -9;
            }
            int obu_length_size = this.bitReader.byte_offset() - obu_header_size;
            int obu_type = obu_header.type;
            if (obu_type != 1 && obu_type != 2 && this.has_sequence_header_ && this.sequence_header_.operating_point_idc[this.operating_point_] != 0 && obu_header.has_extension && (!Obu.inTemporalLayer(this.sequence_header_.operating_point_idc[this.operating_point_], obu_header.temporal_id) || !Obu.inSpatialLayer(this.sequence_header_.operating_point_idc[this.operating_point_], obu_header.spatial_id))) {
                int ll = this.obu_headers_.size() - 1;
                this.obu_headers_.remove(ll);
                this.bitReader.skipBytes(obu_size);
                dataOffset += this.bitReader.byte_offset();
                size -= this.bitReader.byte_offset();
                continue;
            }
            int obu_start_position = this.bitReader.bit_offset();
            boolean obu_skipped = false;
            switch (obu_type) {
                case 2: {
                    break;
                }
                case 1: {
                    if (this.parseSequenceHeader(seen_frame_header)) break;
                    return -9;
                }
                case 3: {
                    if (seen_frame_header) {
                        return -9;
                    }
                    if (!this.parseFrameHeader()) {
                        LogWriter.writeLog("OBU: Failed to parse FrameHeader OBU.");
                        return -9;
                    }
                    frame_header = data;
                    frame_headerPos = dataOffset + obu_start_position >> 3;
                    frame_header_size_in_bits = this.bitReader.bit_offset() - obu_start_position;
                    seen_frame_header = true;
                    parsed_one_full_frame = this.frame_header_.show_existing_frame;
                    break;
                }
                case 7: {
                    if (!seen_frame_header) {
                        return -9;
                    }
                    int fh_size = frame_header_size_in_bits + 7 >> 3;
                    this.bitReader.skipBits(frame_header_size_in_bits);
                    break;
                }
                case 6: {
                    int fh_start_offset = this.bitReader.byte_offset();
                    if (seen_frame_header) {
                        return -9;
                    }
                    if (!this.parseFrameHeader()) {
                        LogWriter.writeLog("OBU: bitstream error");
                        return -9;
                    }
                    if (this.frame_header_.show_existing_frame) {
                        LogWriter.writeLog("OBU: bitstream error");
                        return -9;
                    }
                    if (!this.bitReader.alignToNextByte()) {
                        LogWriter.writeLog("OBU: bitstream error");
                        return -9;
                    }
                    int fh_size = this.bitReader.byte_offset() - fh_start_offset;
                    if (fh_size >= obu_size) {
                        LogWriter.writeLog("OBU: bitstream error");
                        return -9;
                    }
                    int tileGroupSize = obu_size - fh_size;
                    int currentByteOffst = this.bitReader.byte_offset();
                    int byteConsumedSoFar = this.size_ - size + currentByteOffst;
                    if (!this.parseTileGroup(tileGroupSize, byteConsumedSoFar)) {
                        LogWriter.writeLog("OBU: Failed to parse TileGroup in Frame OBU.");
                        return -9;
                    }
                    parsed_one_full_frame = true;
                    break;
                }
                case 4: {
                    if (!this.parseTileGroup(obu_size, this.size_ - size + this.bitReader.byte_offset())) {
                        return -9;
                    }
                    parsed_one_full_frame = this.next_tile_group_start_ == this.frame_header_.tile_info.tile_count;
                    break;
                }
                case 8: {
                    return -7;
                }
                case 15: {
                    if (this.parsePadding(data, dataOffset + obu_start_position >> 3, obu_size)) break;
                    return -9;
                }
                case 5: {
                    LogWriter.writeLog("metadata not supported");
                    break;
                }
                default: {
                    this.bitReader.skipBytes(obu_size);
                    obu_skipped = true;
                }
            }
            if (obu_size > 0 && !obu_skipped && obu_type != 6 && obu_type != 4) {
                int parsed_obu_size_in_bits = this.bitReader.bit_offset() - obu_start_position;
                if (obu_size * 8 < parsed_obu_size_in_bits) {
                    return -9;
                }
                if (!this.bitReader.skipTrailingBits(obu_size * 8 - parsed_obu_size_in_bits)) {
                    return -9;
                }
            }
            int bytes_consumed = this.bitReader.byte_offset();
            int consumed_obu_size = bytes_consumed - obu_length_size - obu_header_size;
            dataOffset += bytes_consumed;
            size -= bytes_consumed;
        }
        if (!parsed_one_full_frame && seen_frame_header) {
            return -9;
        }
        this.data_ = data;
        this.dataOffset_ = dataOffset;
        this.size_ = size;
        return 0;
    }

    byte[] getAV1CodecConfigurationBox(byte[] data, int dataOffset, int size, int[] av1CSize) {
        if (data == null || av1CSize == null) {
            return null;
        }
        D.ObuSequenceHeader sequence_header = new D.ObuSequenceHeader();
        int[] sequence_header_offset = new int[1];
        int[] sequence_header_size = new int[1];
        int status = this.parseBasicStreamInfo(data, dataOffset, size, sequence_header, sequence_header_offset, sequence_header_size);
        if (status != 0) {
            av1CSize[0] = 0;
            return null;
        }
        byte[] av1c = new byte[av1CSize[0]];
        av1c[0] = -127;
        int seq_level_idx_0 = sequence_header.level[0].major - 2 << 2 | sequence_header.level[0].minor;
        av1c[1] = (byte)(sequence_header.profile << 5 | seq_level_idx_0);
        int high_bitdepth = sequence_header.color_config.bitdepth > 8 ? 1 : 0;
        int twelve_bit = sequence_header.color_config.bitdepth == 12 ? 1 : 0;
        av1c[2] = (byte)(sequence_header.tier[0] << 7 | high_bitdepth << 6 | twelve_bit << 5 | (sequence_header.color_config.is_monochrome ? 1 : 0) << 4 | sequence_header.color_config.subsampling_x << 3 | sequence_header.color_config.subsampling_y << 2 | sequence_header.color_config.chroma_sample_position);
        av1c[3] = 0;
        Mem.cpy(av1c, 4, data, dataOffset + sequence_header_offset[0], sequence_header_size[0]);
        return av1c;
    }

    private int parseBasicStreamInfo(byte[] data, int dataOffset, int size, D.ObuSequenceHeader sequence_header, int[] sequence_header_offset, int[] sequence_header_size) {
        D.DecoderState state = new D.DecoderState();
        Obu parser = new Obu(null, 0, 0, null, state);
        parser.bitReader = new RawBit(data, dataOffset, size);
        while (!parser.bitReader.finished()) {
            int obu_start_offset = parser.bitReader.byte_offset();
            if (!parser.parseHeader()) {
                return -9;
            }
            int last = parser.obu_headers_.size() - 1;
            D.ObuHeader obu_header = parser.obu_headers_.get(last);
            if (!obu_header.has_size_field) {
                return -7;
            }
            int obu_size = (int)parser.bitReader.readUnsignedLeb128();
            if (obu_size == 0) {
                return -9;
            }
            if (size - parser.bitReader.byte_offset() < obu_size) {
                LogWriter.writeLog("OBU: bistream error Not enough bits");
                return -9;
            }
            if (obu_header.type != 1) {
                int ll = parser.obu_headers_.size() - 1;
                parser.obu_headers_.remove(ll);
                parser.bitReader.skipBytes(obu_size);
                continue;
            }
            int obu_start_position = parser.bitReader.bit_offset();
            if (!parser.parseSequenceHeader(false)) {
                return -9;
            }
            long obu_size_in_bits = obu_size * 8;
            int parsed_obu_size_in_bits = parser.bitReader.bit_offset() - obu_start_position;
            if (obu_size_in_bits < (long)parsed_obu_size_in_bits) {
                return -9;
            }
            if (!parser.bitReader.skipTrailingBits((int)(obu_size_in_bits - (long)parsed_obu_size_in_bits))) {
                LogWriter.writeLog("OBU: parse streaming info skip error");
                return -9;
            }
            sequence_header = parser.sequence_header_;
            sequence_header_offset[0] = obu_start_offset;
            sequence_header_size[0] = parser.bitReader.byte_offset() - obu_start_offset;
            return 0;
        }
        return -9;
    }
}
