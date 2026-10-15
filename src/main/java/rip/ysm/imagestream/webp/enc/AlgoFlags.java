package rip.ysm.imagestream.webp.enc;

enum AlgoFlags {
   NO_REF_LAST,
   NO_REF_GF,
   NO_REF_ARF,
   NO_UPD_LAST,
   NO_UPD_GF,
   NO_UPD_ARF,
   FORCE_GF,
   FORCE_ARF,
   NO_UPD_ENTROPY,
   FORCE_KF;
}
