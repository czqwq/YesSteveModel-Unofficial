package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

class CodecPriv {
   final EnumSet<InitFlags> init_flags = EnumSet.noneOf(InitFlags.class);
   final EncData enc = new EncData();
   CodecAlgPRiv priv;
}
