/*
 *  “Lean” TextFigure  – agora com **alinhamento horizontal** opcional.
 *
 *  ▸ Recursos mantidos do esboço anterior:
 *      • texto e fonte configuráveis
 *      • deslocamento, cálculo de displayBox, persistência
 *  ▸ Novo recurso adicionado:
 *      • atributo “ALIGN” com três valores: LEFT (padrão), CENTER, RIGHT
 *        - afeta apenas a forma de desenhar; não altera displayBox
 *
 *  Todo o aparato pesado herdado de AttributeFigure continua intocado
 *  (Refused Bequest). O alinhamento é simples o bastante para ser útil
 *  em 99 % dos casos sem reintroduzir muita complexidade.
 */
package org.jhotdraw.figures;

import org.jhotdraw.framework.*;
import org.jhotdraw.util.*;

import java.awt.*;
import java.io.*;

public class TextFigure extends AttributeFigure implements TextHolder {
	
    private int    originX, originY;
    private String text = "";
    private Font   font = new Font("Helvetica", Font.PLAIN, 12);

    public enum Align { LEFT, CENTER, RIGHT }
    private Align  align = Align.LEFT;

    // cache de tamanho 
    private transient boolean sizeDirty = true;
    private transient int cachedW, cachedH;

    public TextFigure() {
        setAttribute(FigureAttributeConstant.FILL_COLOR, ColorMap.color("None"));
    }

    //  Geometria                                                         
    @Override public Rectangle displayBox() {
        Dimension d = textExtent();
        return new Rectangle(originX, originY, d.width, d.height);
    }
    @Override public void basicDisplayBox(Point p, Point ignored) {
        originX = p.x; originY = p.y;
    }
    @Override public void moveBy(int dx, int dy) {
        willChange(); originX += dx; originY += dy; changed();
    }

    //  Texto, fonte e alinhamento                                        
    public String getText()          { return text;  }
    public void   setText(String t){ if(t!=null && !t.equals(text)){ willChange(); text=t; markDirty(); changed(); }}
    public Font   getFont()          { return font;  }
    public void   setFont(Font f)   { if(f!=null){ willChange(); font=f; markDirty(); changed(); }}
    public Align  getAlign()         { return align; }
    public void   setAlign(Align a) { if(a!=null && a!=align){ willChange(); align=a; changed(); }}

    //  Desenho                                                           

    @Override public void drawBackground(Graphics g) {
        Rectangle r = displayBox();
        g.fillRect(r.x, r.y, r.width, r.height);
    }
    @Override public void drawFrame(Graphics g) {
        g.setFont(font);
        g.setColor((Color) getAttribute(FigureAttributeConstant.TEXT_COLOR));
        FontMetrics fm = Toolkit.getDefaultToolkit().getFontMetrics(font);
        Rectangle r = displayBox();

        int textX = switch (align) {
            case LEFT   -> r.x;
            case CENTER -> r.x + (r.width  - fm.stringWidth(text))/2;
            case RIGHT  -> r.x + (r.width  - fm.stringWidth(text));
        };
        int textY = r.y + fm.getAscent();
        g.drawString(text, textX, textY);
    }

  
    //  Handles                                             
    @Override public HandleEnumeration handles() { return HandleEnumerator.EMPTY; }

    //  Persistência                                                      

    @Override public void write(StorableOutput out) {
        super.write(out);
        out.writeInt(originX); out.writeInt(originY);
        out.writeString(text);
        out.writeString(font.getName()); out.writeInt(font.getStyle()); out.writeInt(font.getSize());
        out.writeString(align.name());
    }
    @Override public void read(StorableInput in) throws IOException {
        super.read(in);
        originX = in.readInt(); originY = in.readInt();
        text    = in.readString();
        font    = new Font(in.readString(), in.readInt(), in.readInt());
        align   = Align.valueOf(in.readString());
        markDirty();
    }

    //  TextHolder mínimo                                                 
    @Override public Rectangle textDisplayBox(){ return displayBox(); }
    @Override public int       overlayColumns(){ return Math.max(20, text.length()+3); }
    @Override public boolean   acceptsTyping() { return true; }
    @Override public Figure    getRepresentingFigure(){ return this; }

    //  Helpers                                                           
    private Dimension textExtent() {
        if (!sizeDirty) return new Dimension(cachedW, cachedH);
        FontMetrics fm = Toolkit.getDefaultToolkit().getFontMetrics(font);
        cachedW = fm.stringWidth(text); cachedH = fm.getHeight(); sizeDirty=false;
        return new Dimension(cachedW, cachedH);
    }
    private void markDirty(){ sizeDirty=true; }

}
