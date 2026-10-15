package rip.ysm.imagestream.webp.enc;

final class Entropy {
   static final int BLOCK_TYPES = 4;
   static final int COEF_BANDS = 8;
   static final int PREV_COEF_CONTEXTS = 3;
   static final int ENTROPY_NODES = 11;
   static final int DCT_MAX_VALUE = 2048;
   static final short[] vp8_coef_tree_shorts = new short[]{
      (short)(-TokenAlphabet.DCT_EOB_TOKEN.ordinal()),
      2,
      (short)(-TokenAlphabet.ZERO_TOKEN.ordinal()),
      4,
      (short)(-TokenAlphabet.ONE_TOKEN.ordinal()),
      6,
      8,
      12,
      (short)(-TokenAlphabet.TWO_TOKEN.ordinal()),
      10,
      (short)(-TokenAlphabet.THREE_TOKEN.ordinal()),
      (short)(-TokenAlphabet.FOUR_TOKEN.ordinal()),
      14,
      16,
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY1.ordinal()),
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY2.ordinal()),
      18,
      20,
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY3.ordinal()),
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY4.ordinal()),
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY5.ordinal()),
      (short)(-TokenAlphabet.DCT_VAL_CATEGORY6.ordinal())
   };
   static final GetSetPointer vp8_coef_tree = new GetSetPointer(vp8_coef_tree_shorts, 0);
   static final int[] vp8_mb_feature_data_bits = new int[]{7, 6};

   private Entropy() {
   }
}
