package rip.ysm.imagestream.webp.enc;

final class RationalLarge {
   final long num;
   final long den;

   RationalLarge(long num, long den) {
      this.num = num;
      this.den = den;
   }

   long getNum() {
      return this.num;
   }

   long getDen() {
      return this.den;
   }

   @Override
   public int hashCode() {
      int prime = 31;
      int result = 1;
      result = 31 * result + (int)(this.den ^ this.den >>> 32);
      return 31 * result + (int)(this.num ^ this.num >>> 32);
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
         RationalLarge other = (RationalLarge)obj;
         return this.den == other.den && this.num == other.num;
      }
   }

   double scalar() {
      return (double)this.num / this.den;
   }

   static RationalLarge reduceLong(long num, long den) {
      long gcd = gcdLong(num, den);
      return new RationalLarge(num / gcd, den / gcd);
   }

   private static long gcdLong(long a, long b) {
      return b != 0L ? gcdLong(b, a % b) : a;
   }
}
