package rip.ysm.imagestream.webp.enc;

enum TokenAlphabet {
   ZERO_TOKEN(null, null, 0, 0, 0, new Token(2, 2)),
   ONE_TOKEN(null, null, 0, 1, 1, new Token(6, 3)),
   TWO_TOKEN(null, null, 0, 2, 2, new Token(28, 5)),
   THREE_TOKEN(null, null, 0, 3, 2, new Token(58, 6)),
   FOUR_TOKEN(null, null, 0, 4, 2, new Token(59, 6)),
   DCT_VAL_CATEGORY1(new short[]{0, 0}, W.Pcat1, 1, 5, 2, new Token(60, 6)),
   DCT_VAL_CATEGORY2(new short[]{2, 2, 0, 0}, W.Pcat2, 2, 7, 2, new Token(61, 6)),
   DCT_VAL_CATEGORY3(new short[]{2, 2, 4, 4, 0, 0}, W.Pcat3, 3, 11, 2, new Token(124, 7)),
   DCT_VAL_CATEGORY4(new short[]{2, 2, 4, 4, 6, 6, 0, 0}, W.Pcat4, 4, 19, 2, new Token(125, 7)),
   DCT_VAL_CATEGORY5(new short[]{2, 2, 4, 4, 6, 6, 8, 8, 0, 0}, W.Pcat5, 5, 35, 2, new Token(126, 7)),
   DCT_VAL_CATEGORY6(new short[]{2, 2, 4, 4, 6, 6, 8, 8, 10, 10, 12, 12, 14, 14, 16, 16, 18, 18, 20, 20, 0, 0}, W.Pcat6, 11, 67, 2, new Token(127, 7)),
   DCT_EOB_TOKEN(null, null, 0, 0, 0, new Token(0, 1));

   static final int entropyTokenCount = values().length;
   final short prevTokenClass;
   final short len;
   final short base_val;
   final short[] tree;
   final short[] prob;
   final short coefEncLen;
   final short coefEncValue;

   private TokenAlphabet(final short[] t, final short[] p, final int l, final int b, final int ptc, final Token coef) {
      this.tree = t;
      this.prob = p;
      this.len = (short)l;
      this.base_val = (short)b;
      this.prevTokenClass = (short)ptc;
      this.coefEncLen = coef.len;
      this.coefEncValue = coef.value;
   }
}
