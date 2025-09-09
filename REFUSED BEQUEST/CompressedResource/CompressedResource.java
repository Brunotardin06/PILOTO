package org.apache.tools.ant.types.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.util.FileUtils;

/**
 * Recurso que realiza (des)compressão “on‑the‑fly”
 * mantendo toda a interface de {@link Resource}.
 *
 * <p>Todos os mutators herdados são rejeitados
 * — <i>Refused Bequest</i> — já que alterariam
 * incoerentemente o estado do recurso interno.</p>
 *
 * @since Ant 1.7
 */
public abstract class CompressedResource extends Resource {

    private Resource wrapped;

    /* ------------------------------------------------------------------ *
     *  Construtores                                                       *
     * ------------------------------------------------------------------ */

    public CompressedResource() { }

    public CompressedResource(ResourceCollection rc) {
        addConfigured(rc);
    }

    /* ------------------------------------------------------------------ *
     *  Configuração                                                       *
     * ------------------------------------------------------------------ */

    public void addConfigured(ResourceCollection rc) {
        checkChildrenAllowed();
        if (wrapped != null) {
            throw new BuildException("only one nested resource allowed");
        }
        if (rc.size() != 1) {
            throw new BuildException("compressed resource needs exactly one"
                                    + " nested resource");
        }
        wrapped = (Resource) rc.iterator().next();
    }

    /* ------------------------------------------------------------------ *
     *  Delegação                                                          *
     * ------------------------------------------------------------------ */

    public String  getName()          { return delegate().getName();       }
    public boolean isExists()         { return delegate().isExists();      }
    public long    getLastModified()  { return delegate().getLastModified();}
    public boolean isDirectory()      { return delegate().isDirectory();   }
    public boolean isFilesystemOnly() { return false;                      }

    public int compareTo(Object o) {
        return o instanceof CompressedResource
             ? delegate().compareTo(((CompressedResource) o).delegate())
             : delegate().compareTo(o);
    }

    public int hashCode() { return delegate().hashCode(); }

    /* ------------------------------------------------------------------ *
     *  Tamanho                                                            *
     * ------------------------------------------------------------------ */

    public long getSize() {
        if (!isExists()) return 0L;

        long known = delegate().getSize();
        if (known != UNKNOWN_SIZE) return known;

        try (InputStream in = getInputStream()) {
            byte[] buf = new byte[8 * 1024];
            long   n   = 0L;
            for (int r; (r = in.read(buf)) > 0; ) n += r;
            return n;
        } catch (IOException e) {
            throw new BuildException("unable to read " + getName(), e);
        }
    }

    /* ------------------------------------------------------------------ *
     *  Streams                                                            *
     * ------------------------------------------------------------------ */

    public InputStream  getInputStream()  throws IOException {
        InputStream in = delegate().getInputStream();
        return in == null ? null : wrapStream(in);
    }

    public OutputStream getOutputStream() throws IOException {
        OutputStream out = delegate().getOutputStream();
        return out == null ? null : wrapStream(out);
    }


    public void setName        (String  s)    { reject("name");        }
    public void setExists      (boolean b)    { reject("exists");      }
    public void setLastModified(long    t)    { reject("lastModified");}
    public void setDirectory   (boolean b)    { reject("directory");   }
    public void setSize        (long    l)    { reject("size");        }

    /* ------------------------------------------------------------------ *
     *  Descrição                                                          *
     * ------------------------------------------------------------------ */

    public String toString() {
        return getCompressionName() + " compressed " + delegate();
    }

    /* ------------------------------------------------------------------ *
     *  Internos                                                           *
     * ------------------------------------------------------------------ */

    protected abstract InputStream  wrapStream(InputStream  in ) throws IOException;
    protected abstract OutputStream wrapStream(OutputStream out) throws IOException;
    protected abstract String getCompressionName();

    private Resource delegate() {
        if (isReference()) return (Resource) getCheckedRef();
        if (wrapped == null) throw new BuildException("no nested resource");
        return wrapped;
    }

    private static void reject(String attr) {
        throw new BuildException("cannot set '" + attr + "' on compressed resource");
    }

  
    public void setRefid(Reference r) {
        if (wrapped != null) throw noChildrenAllowed();
        super.setRefid(r);
    }
}
