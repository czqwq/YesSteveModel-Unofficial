package rip.ysm.imagestream.webp.enc;

class SpeedFeatures {
   boolean RD;
   SearchMethods search_method;
   boolean improved_quant;
   boolean auto_filter;
   int recode_loop;
   boolean iterative_sub_pixel;
   boolean half_pixel_search;
   boolean quarter_pixel_search;
   final int[] thresh_mult = new int[20];
   int max_step_search_steps;
   int first_step;
   boolean optimize_coefficients;
   boolean use_fastquant_for_pick;
   boolean no_skip_block4x4_search;
   boolean improved_mv_pred;
}
