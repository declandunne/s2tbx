package org.esa.s2tbx.dataio.gdal.writer.plugins;

/**
 * @author Jean Coravu
 */
public class MFFDriverProductWriterPlugInTest extends AbstractDriverProductWriterPlugInTest {

    public MFFDriverProductWriterPlugInTest() {
        super("MFF", new MFFDriverProductWriterPlugIn());
    }
}