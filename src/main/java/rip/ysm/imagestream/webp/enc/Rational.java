package rip.ysm.imagestream.webp.enc;

class Rational {
   static final Rational ONE = new Rational((byte)1, (byte)1);
   final byte num;
   final byte den;

   static Rational R(byte num, byte den) {
      return new Rational(num, den);
   }

   Rational(byte num, byte den) {
      this.num = num;
      this.den = den;
   }

   Rational flip() {
      return new Rational(this.den, this.num);
   }

   @Override
   public int hashCode() {
      int prime = 31;
      int result = 1;
      result = 31 * result + this.den;
      return 31 * result + this.num;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj) {
         return true;
      } else if (obj == null) {
         return false;
      } else if (this.getClass() != obj.getClass()) {
         return false;
      } else {
         Rational other = (Rational)obj;
         return this.den == other.den && this.num == other.num;
      }
   }

   double toDouble() {
      return (double)this.num / this.den;
   }
}
