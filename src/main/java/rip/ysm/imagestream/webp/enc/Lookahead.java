package rip.ysm.imagestream.webp.enc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

class Lookahead {
   static final int PEEK_FORWARD = 1;
   static final int PEEK_BACKWARD = -1;
   static final int MAX_LAG_BUFFERS = 25;
   final int max_sz;
   int sz;
   int read_idx;
   int write_idx;
   List<LookaheadEntry> buffer;

   void vp8_lookahead_destroy() {
      if (this.buffer != null) {
         this.buffer = null;
      }
   }

   int vp8_lookahead_depth() {
      return this.sz;
   }

   LookaheadEntry pop(boolean read) {
      int curidx = read ? this.read_idx : this.write_idx;
      LookaheadEntry buf = this.buffer.get(curidx);
      if (++curidx >= this.max_sz) {
         curidx -= this.max_sz;
      }

      if (read) {
         this.read_idx = curidx;
      } else {
         this.write_idx = curidx;
      }

      return buf;
   }

   void vp8_lookahead_push(YV12buffer src, long ts_start, long ts_end, EnumSet<FrameFlags> flags, FullGetSetPointer active_map) {
      int mb_rows = src.y_height + 15 >> 4;
      int mb_cols = src.y_width + 15 >> 4;
      if (this.sz + 2 <= this.max_sz) {
         this.sz++;
         LookaheadEntry buf = this.pop(false);
         if (this.max_sz == 1 && active_map != null && flags.isEmpty()) {
            label49:
            for (int row = 0; row < mb_rows; row++) {
               int col = 0;
               int baseidx = row * mb_cols;

               while (true) {
                  while (col >= mb_cols || active_map.getRel(baseidx + col) != 0) {
                     if (col == mb_cols) {
                        continue label49;
                     }

                     int active_end = col;

                     while (active_end < mb_cols && active_map.getRel(baseidx + active_end) != 0) {
                        active_end++;
                     }

                     Extend.vp8_copy_and_extend_frame_with_rect(src, buf.img, row << 4, col << 4, 16, active_end - col << 4);
                     col = active_end;
                  }

                  col++;
               }
            }
         } else {
            Extend.vp8_copy_and_extend_frame(src, buf.img);
         }

         buf.ts_start = ts_start;
         buf.ts_end = ts_end;
         buf.flags = flags;
      }
   }

   LookaheadEntry vp8_lookahead_peek(int index, int direction) {
      LookaheadEntry buf = null;
      if (direction == 1) {
         if (index < this.sz) {
            index += this.read_idx;
            if (index >= this.max_sz) {
               index -= this.max_sz;
            }

            buf = this.buffer.get(index);
         }
      } else if (direction == -1) {
         if (this.read_idx == 0) {
            index = this.max_sz - 1;
         } else {
            index = this.read_idx - index;
         }

         buf = this.buffer.get(index);
      }

      return buf;
   }

   LookaheadEntry vp8_lookahead_pop(boolean drain) {
      LookaheadEntry buf = null;
      if (this.sz != 0 && (drain || this.sz == this.max_sz - 1)) {
         buf = this.pop(true);
         this.sz--;
      }

      return buf;
   }

   Lookahead(int width, int height, int depth) {
      if (depth < 1) {
         depth = 1;
      } else if (depth > 25) {
         depth = 25;
      }

      depth++;
      width = width + 15 & -16;
      height = height + 15 & -16;
      this.max_sz = depth;
      this.buffer = new ArrayList<>(depth);

      for (int i = 0; i < depth; i++) {
         LookaheadEntry la = new LookaheadEntry();
         this.buffer.add(la);
         la.img = new YV12buffer(width, height);
      }
   }
}
