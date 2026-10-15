package rip.ysm.imagestream.webp.enc;

class TokenValue {
   final TokenAlphabet token;
   final int extra;

   TokenValue(TokenAlphabet token, int extra) {
      this.token = token;
      this.extra = extra;
   }
}
