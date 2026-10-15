package rip.ysm.imagestream.jpeg.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Frame {
   public int precision;
   public int scanH;
   public int scanV;
   public int mcusX;
   public int mcusY;
   public boolean progressive;
   public boolean extended;
   public int maxH;
   public int maxV;
   public final List<Component> components = new ArrayList<>();
   public final HashMap<Integer, Integer> componentID = new HashMap<>();
}
