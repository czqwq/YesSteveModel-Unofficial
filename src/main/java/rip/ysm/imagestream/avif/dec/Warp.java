package rip.ysm.imagestream.avif.dec;

class Warp {
   static final int bitdepth = 8;

   static D.WarpFilterParams GetWarpFilterParams(int src_x, int src_y, int subsampling_x, int subsampling_y, int[] warp_params) {
      D.WarpFilterParams filter_params = new D.WarpFilterParams();
      long dst_x = src_x * 1L * warp_params[2] + src_y * 1L * warp_params[3] + warp_params[0];
      long dst_y = src_x * 1L * warp_params[4] + src_y * 1L * warp_params[5] + warp_params[1];
      filter_params.x4 = dst_x >> subsampling_x;
      filter_params.y4 = dst_y >> subsampling_y;
      filter_params.ix4 = (int)(filter_params.x4 >> 16);
      filter_params.iy4 = (int)(filter_params.y4 >> 16);
      return filter_params;
   }
}
