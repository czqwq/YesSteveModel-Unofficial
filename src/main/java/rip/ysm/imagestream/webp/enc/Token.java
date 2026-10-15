package rip.ysm.imagestream.webp.enc;

class Token {
   static final Token[] vp8_bmode_encodings = new Token[]{
      new Token(0, 1),
      new Token(2, 2),
      new Token(6, 3),
      new Token(28, 5),
      new Token(30, 5),
      new Token(58, 6),
      new Token(59, 6),
      new Token(62, 6),
      new Token(126, 7),
      new Token(127, 7)
   };
   static final Token[] vp8_kf_ymode_encodings = new Token[]{new Token(4, 3), new Token(5, 3), new Token(6, 3), new Token(7, 3), new Token(0, 1)};
   static final Token[] vp8_uv_mode_encodings = new Token[]{new Token(0, 1), new Token(2, 2), new Token(6, 3), new Token(7, 3)};
   final short value;
   final short len;

   Token(int v, int l) {
      this.value = (short)v;
      this.len = (short)l;
   }
}
