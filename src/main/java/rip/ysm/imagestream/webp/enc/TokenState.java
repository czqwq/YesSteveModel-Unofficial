package rip.ysm.imagestream.webp.enc;

class TokenState {
   int rate;
   final int error;
   final int next;
   TokenAlphabet token;
   final int qc;

   TokenState(int r, int e, int n, TokenAlphabet t, int q) {
      this.rate = r;
      this.error = e;
      this.next = n;
      this.token = t;
      this.qc = q;
   }

   TokenState(TokenState other) {
      this.rate = other.rate;
      this.error = other.error;
      this.next = other.next;
      this.token = other.token;
      this.qc = other.qc;
   }
}
