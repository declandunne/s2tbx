package org.esa.s2tbx.s2msi.aerosol;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s2tbx.s2msi.aerosol.lut.Luts;
import org.esa.s2tbx.s2msi.aerosol.lut.MomoLut;
import org.esa.s2tbx.s2msi.aerosol.math.BrentFitFunction;
import org.esa.s2tbx.s2msi.aerosol.util.AerosolUtils;
import org.esa.s2tbx.s2msi.aerosol.util.PixelGeometry;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.Guardian;

import javax.imageio.stream.ImageInputStream;
import javax.media.jai.BorderExtender;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * todo: add comment
 * To change this template use File | Settings | File Templates.
 * Date: 22.09.2016
 * Time: 14:25
 *
 * @author olafd
 */
@OperatorMetadata(alias = "S2AerosolOp", internal = true)
public class S2AerosolOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    // todo: define what we need from this
    private String surfaceSpecName = "surface_reflectance_spec.asc";
    @Parameter(defaultValue = "2")
    private int vegSpecId = 2;

    @Parameter(defaultValue = "1")
    private int soilSpecId = 1;

    @Parameter(defaultValue = "9")
    private int scale = 9;

    @Parameter(defaultValue = "0.2")
    private float ndviThreshold = 0.2f;


    private String productName;
    private String productType;

    private int srcRasterWidth;
    private int srcRasterHeight;
    private int tarRasterWidth;
    private int tarRasterHeight;

    private int nSpecWvl;
    private float[][] specWvl;
    private double[] soilSurfSpec;
    private double[] vegSurfSpec;
    private double[] specWeights;

    private MomoLut momo;

    private BorderExtender borderExt;
    private Rectangle pixelWindow;

    private Band validBand;
    private Band aotBand;
    private Band aotErrorBand;
    private Band latBand;


    private boolean addFitBands = false;
    private Map<String, Double> sourceNoDataValues;

    private String validName;
    private String[] auxBandNames;

    @Override
    public void initialize() throws OperatorException {
        productName = sourceProduct.getName() + "_AOT";
        productType = sourceProduct.getProductType() + "_AOT";

        initRasterDimensions(sourceProduct, 9);

        final String validExpression = InstrumentConsts.VALID_RETRIEVAL_EXPRESSION;
        validBand = AerosolUtils.createBooleanExpressionBand(validExpression, sourceProduct);
        validName = validBand.getName();

        auxBandNames = new String[]{
                InstrumentConsts.SURFACE_PRESSURE_NAME,
                InstrumentConsts.OZONE_NAME,
                validName,
                InstrumentConsts.NDVI_NAME
        };

        specWeights = AerosolUtils.normalize(InstrumentConsts.FIT_WEIGHTS);
        specWvl = getSpectralWvl(InstrumentConsts.REFLEC_NAMES);
        nSpecWvl = specWvl[0].length;
        readSurfaceSpectra(surfaceSpecName);

        // in the source product we have:
        // - B1,...,B12
        // - sun_zenith, sun_azimuth, view_zenith_mean, view_azimuth_mean
        // - pixel_classif_flag
        // - elevation
        // - surfPressEstimate

        if (!sourceProduct.containsBand(InstrumentConsts.NDVI_NAME)) {
            createNdviBand();
        }

        sourceNoDataValues = getSourceNoDataValues(InstrumentConsts.GEOM_NAMES);
        sourceNoDataValues.putAll(getSourceNoDataValues(InstrumentConsts.REFLEC_NAMES));
        sourceNoDataValues.putAll(getSourceNoDataValues(auxBandNames));

        try {
            createMomoLookupTable();
        } catch (IOException e) {
            throw new OperatorException("Failed to read LUTs. " + e.getMessage(), e);
        }

        borderExt = BorderExtender.createInstance(BorderExtender.BORDER_COPY);
        pixelWindow = new Rectangle(0, 0, scale, scale);

        createTargetProduct();
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        Rectangle srcRec = getSourceRectangle(targetRectangle, pixelWindow);

        if (!containsTileValidData(srcRec)) {
            setInvalidTargetSamples(targetTiles);
            return;
        }

        Map<String, Tile> sourceTiles = getSourceTiles(InstrumentConsts.GEOM_NAMES, srcRec, borderExt);
        sourceTiles.putAll(getSourceTiles(InstrumentConsts.REFLEC_NAMES, srcRec, borderExt));
        sourceTiles.putAll(getSourceTiles(auxBandNames, srcRec, borderExt));

        int x0 = (int) targetRectangle.getX();
        int y0 = (int) targetRectangle.getY();
        int width = (int) targetRectangle.getWidth() + x0 - 1;
        int height = (int) targetRectangle.getHeight() + y0 - 1;

        for (int iY = y0; iY <= height; iY++) {
            checkForCancellation();
            for (int iX = x0; iX <= width; iX++) {
                processSuperPixel(sourceTiles, iX, iY, targetTiles);
            }
            pm.worked(1);
        }
        pm.done();
    }

    private void processSuperPixel(Map<String, Tile> sourceTiles, int iX, int iY, Map<Band, Tile> targetTiles) {
        // read pixel data and init brent fit
        BrentFitFunction brentFitFunction = null;
        InputPixelData[] inPixField = readDarkestNPixels(sourceTiles, iX, iY, pixelWindow);
        if (inPixField != null) {
            brentFitFunction = new BrentFitFunction(BrentFitFunction.SPECTRAL_MODEL, inPixField, momo, specWeights, soilSurfSpec, vegSurfSpec);
        }
        retrieveAndSetTarget(inPixField, brentFitFunction, targetTiles, iX, iY);
    }

    private void retrieveAndSetTarget(InputPixelData[] inPixField, BrentFitFunction brentFitFunction, Map<Band, Tile> targetTiles, int iX, int iY) {
        // run retrieval and set target samples
        if (inPixField != null) {
            RetrievalResults result = executeRetrieval(brentFitFunction);
            if (!result.isRetrievalFailed()) {
                setTargetSamples(targetTiles, iX, iY, result);
            } else {
                setInvalidTargetSamples(targetTiles, iX, iY);
            }
        } else {
            setInvalidTargetSamples(targetTiles, iX, iY);
        }
    }

    private RetrievalResults executeRetrieval(BrentFitFunction brentFitFunction) {
        final double maxAOT = brentFitFunction.getMaxAOT();
        final PointRetrieval pR = new PointRetrieval(brentFitFunction);
        return pR.runRetrieval(maxAOT);
    }

    private InputPixelData createInPixelData(double[] tileValues) {
        PixelGeometry geomNadir;
        PixelGeometry geomFward;
        double[][] toaRefl = new double[2][nSpecWvl];
        int skip = 0;
            geomNadir = new PixelGeometry(tileValues[0], tileValues[1], tileValues[2], tileValues[3]);
            geomFward = geomNadir;
            skip += 4;
        for (int i = 0; i < nSpecWvl; i++) {
            toaRefl[0][i] = tileValues[skip + i];
            toaRefl[1][i] = tileValues[skip + i];
        }
        skip += nSpecWvl;
        double surfP = Math.min(tileValues[skip], 1013.25);
        double o3DU = ensureO3DobsonUnits(tileValues[skip + 1]);
        float wvCol = 2.5f;
        return new InputPixelData(geomNadir, geomFward, surfP, o3DU, wvCol, specWvl[0], toaRefl[0], toaRefl[1]);
    }

    private void createTargetProduct() {
        targetProduct = new Product(productName, productType, tarRasterWidth, tarRasterHeight);
        createTargetProductBands();
        setTargetProduct(targetProduct);
    }

    private void createTargetProductBands() {
        aotBand = AerosolUtils.createTargetBand(AotConsts.aot, tarRasterWidth, tarRasterHeight);
        targetProduct.addBand(aotBand);

        aotErrorBand = AerosolUtils.createTargetBand(AotConsts.aotErr, tarRasterWidth, tarRasterHeight);
        aotErrorBand.setValidPixelExpression(InstrumentConsts.VALID_RETRIEVAL_EXPRESSION);
        targetProduct.addBand(aotErrorBand);
        latBand = new Band("latitude", ProductData.TYPE_FLOAT32, tarRasterWidth, tarRasterHeight);
        targetProduct.addBand(latBand);

        if (addFitBands) {
            Band targetBand = new Band("fit_err", ProductData.TYPE_FLOAT32, tarRasterWidth, tarRasterHeight);
            targetBand.setDescription("aot uncertainty");
            targetBand.setNoDataValue(-1);
            targetBand.setNoDataValueUsed(true);
            targetBand.setValidPixelExpression("");
            targetBand.setUnit("dl");
            targetProduct.addBand(targetBand);

            targetBand = new Band("fit_curv", ProductData.TYPE_FLOAT32, tarRasterWidth, tarRasterHeight);
            targetBand.setDescription("aot uncertainty");
            targetBand.setNoDataValue(-1);
            targetBand.setNoDataValueUsed(true);
            targetBand.setValidPixelExpression("");
            targetBand.setUnit("dl");
            targetProduct.addBand(targetBand);
        }
    }

    private Rectangle getSourceRectangle(Rectangle targetRectangle, Rectangle pixelWindow) {
        return new Rectangle(targetRectangle.x * pixelWindow.width + pixelWindow.x,
                             targetRectangle.y * pixelWindow.height + pixelWindow.y,
                             targetRectangle.width * pixelWindow.width,
                             targetRectangle.height * pixelWindow.height);
    }

    private void initRasterDimensions(Product sourceProduct, int scale) {
        srcRasterHeight = sourceProduct.getSceneRasterHeight();
        srcRasterWidth = sourceProduct.getSceneRasterWidth();
        tarRasterHeight = srcRasterHeight / scale;
        tarRasterWidth = srcRasterWidth / scale;
    }

    private Map<String, Tile> getSourceTiles(String[] bandNames, Rectangle srcRec, BorderExtender borderExt) {
        Map<String, Tile> tileMap = new HashMap<String, Tile>(bandNames.length);
        for (String name : bandNames) {
            RasterDataNode b = (name.equals(validName)) ? validBand : sourceProduct.getRasterDataNode(name);
            tileMap.put(name, getSourceTile(b, srcRec, borderExt));
        }
        return tileMap;
    }

    private Map<String, Double> getSourceNoDataValues(String[] bandNames) {
        Map<String, Double> noDataMap = new HashMap<String, Double>(bandNames.length);
        for (String name : bandNames) {
            RasterDataNode b = (name.equals(validName)) ? validBand : sourceProduct.getRasterDataNode(name);
            noDataMap.put(name, b.getGeophysicalNoDataValue());
        }
        return noDataMap;
    }

    private InputPixelData[] readAveragePixel(Map<String, Tile> sourceTiles, int iX, int iY, Rectangle pixelWindow) {
        InputPixelData[] inPixField = null;

        boolean valid = uniformityTest(sourceTiles, iX, iY);
        if (!valid) return null;

        double[] tileValues = new double[sourceTiles.size()];
        double[] sumVal = new double[sourceTiles.size()];
        for (int k = 0; k < sumVal.length; k++) sumVal[k] = 0;
        int nAve = 0;
        int xOffset = iX * pixelWindow.width + pixelWindow.x;
        int yOffset = iY * pixelWindow.height + pixelWindow.y;
        for (int y = yOffset; y < yOffset + pixelWindow.height; y++) {
            for (int x = xOffset; x < xOffset + pixelWindow.width; x++) {
                valid = valid && sourceTiles.get(validName).getSampleBoolean(x, y);
                if (valid) {
                    valid = valid && readAllValues(x, y, sourceTiles, tileValues);
                    if (valid) {
                        addToSumArray(tileValues, sumVal);
                        nAve++;
                    }
                }
            }
        }
        if (nAve > 0.95 * pixelWindow.width * pixelWindow.height) {
            for (int i = 0; i < sumVal.length; i++) sumVal[i] /= nAve;
            InputPixelData ipd = createInPixelData(sumVal);
            if (momo.isInsideLut(ipd)) {
                inPixField = new InputPixelData[]{ipd};
            }
        }
        return inPixField;
    }

    private InputPixelData[] readDarkestNPixels(Map<String, Tile> sourceTiles, int iX, int iY, Rectangle pixelWindow) {
        boolean valid = uniformityTest(sourceTiles, iX, iY);
        if (!valid) return null;

        int NPixel = 10;
        ArrayList<InputPixelData> inPixelList = new ArrayList<InputPixelData>(pixelWindow.height * pixelWindow.width);
        InputPixelData[] inPixField = null;
        float ndvi;
        float[] ndviArr = new float[pixelWindow.height * pixelWindow.width];

        double[] tileValues = new double[sourceTiles.size()];

        int nValid = 0;
        int xOffset = iX * pixelWindow.width + pixelWindow.x;
        int yOffset = iY * pixelWindow.height + pixelWindow.y;
        for (int y = yOffset; y < yOffset + pixelWindow.height; y++) {
            for (int x = xOffset; x < xOffset + pixelWindow.width; x++) {
                valid = sourceTiles.get(validName).getSampleBoolean(x, y);
                final int ndviArrIndex = (y - yOffset) * pixelWindow.width + (x - xOffset);
                ndviArr[ndviArrIndex] = (valid) ? sourceTiles.get(InstrumentConsts.NDVI_NAME).getSampleFloat(x, y) : -1;
                if (valid) nValid++;
            }
        }

        // return null if not enough valid pixels
        if (nValid < 0.95 * pixelWindow.width * pixelWindow.height) return null;

        Arrays.sort(ndviArr);
        if (ndviArr[ndviArr.length - 10 - NPixel] > ndviThreshold) {
            for (int y = yOffset; y < yOffset + pixelWindow.height; y++) {
                for (int x = xOffset; x < xOffset + pixelWindow.width; x++) {
                    valid = sourceTiles.get(validName).getSampleBoolean(x, y);
                    ndvi = sourceTiles.get(InstrumentConsts.NDVI_NAME).getSampleFloat(x, y);
                    if (valid && (ndvi >= ndviArr[ndviArr.length - 10 - NPixel])
                            && (ndvi <= ndviArr[ndviArr.length - 1 - NPixel])) {
                        valid = valid && readAllValues(x, y, sourceTiles, tileValues);
                        InputPixelData ipd = createInPixelData(tileValues);
                        if (valid && momo.isInsideLut(ipd)) {
                            inPixelList.add(ipd);
                        }
                    }
                }
            }
            if (inPixelList.size() > 3) {
                inPixField = new InputPixelData[inPixelList.size()];
                inPixelList.toArray(inPixField);
            }
        }
        return inPixField;
    }

    private void setTargetSamples(Map<Band, Tile> targetTiles, int iX, int iY, RetrievalResults result) {

        float[] latLon = getLatLon(iX, iY, pixelWindow, sourceProduct);
        targetTiles.get(latBand).setSample(iX, iY, latLon[0]);

        targetTiles.get(aotBand).setSample(iX, iY, result.getOptAOT());
        targetTiles.get(aotErrorBand).setSample(iX, iY, result.getRetrievalErr());
        if (addFitBands) {
            targetTiles.get(targetProduct.getBand("fit_err")).setSample(iX, iY, result.getOptErr());
            targetTiles.get(targetProduct.getBand("fit_curv")).setSample(iX, iY, result.getCurvature());
        }
    }

    private void setInvalidTargetSamples(Map<Band, Tile> targetTiles, int iX, int iY) {
        float[] latLon = getLatLon(iX, iY, pixelWindow, sourceProduct);
        for (Tile t : targetTiles.values()) {
            if (t.getRasterDataNode() == latBand) {
                targetTiles.get(targetProduct.getBand("latitude")).setSample(iX, iY, latLon[0]);
            } else {
                t.setSample(iX, iY, t.getRasterDataNode().getNoDataValue());
            }
        }
    }

    private void setInvalidTargetSamples(Map<Band, Tile> targetTiles) {
        for (Tile.Pos pos : targetTiles.get(targetProduct.getBandAt(0))) {
            setInvalidTargetSamples(targetTiles, pos.x, pos.y);
        }
    }

    private void createConstOzoneBand(float ozonAtmCm) {
        String ozoneExpr = String.format("%5.3f", ozonAtmCm);
        VirtualBand ozoneBand = new VirtualBand(InstrumentConsts.OZONE_NAME, ProductData.TYPE_FLOAT32,
                                                srcRasterWidth, srcRasterHeight, ozoneExpr);
        ozoneBand.setDescription("constant ozone band");
        ozoneBand.setNoDataValue(0);
        ozoneBand.setNoDataValueUsed(true);
        ozoneBand.setUnit("atm.cm");
        sourceProduct.addBand(ozoneBand);
    }

    private void createNdviBand() {
        VirtualBand ndviBand = new VirtualBand(InstrumentConsts.NDVI_NAME, ProductData.TYPE_FLOAT32,
                                               srcRasterWidth, srcRasterHeight, InstrumentConsts.NDVI_EXPRESSION);
        sourceProduct.addBand(ndviBand);
    }

    private void readSurfaceSpectra(String fname) {
        Guardian.assertNotNull("specWvl", specWvl);
        final InputStream inputStream = S2AerosolOp.class.getResourceAsStream(fname);
        Guardian.assertNotNull("surface spectra InputStream", inputStream);
        BufferedReader reader;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        float[] fullWvl = new float[10000];    // todo: what's this???
        float[] fullSoil = new float[10000];
        float[] fullVeg = new float[10000];
        int nWvl = 0;
        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!(line.isEmpty() || line.startsWith("#") || line.startsWith("*"))) {
                    String[] stmp = line.split("[ \t]+");
                    fullWvl[nWvl] = Float.valueOf(stmp[0]);
                    if (fullWvl[nWvl] < 100) fullWvl[nWvl] *= 1000; // conversion from um to nm
                    fullSoil[nWvl] = Float.valueOf(stmp[this.soilSpecId]);
                    fullVeg[nWvl] = Float.valueOf(stmp[this.vegSpecId]);
                    nWvl++;
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(S2AerosolOp.class.getName()).log(Level.SEVERE, null, ex);
            throw new OperatorException(ex.getMessage(), ex.getCause());
        }

        soilSurfSpec = new double[nSpecWvl];
        vegSurfSpec = new double[nSpecWvl];
        int j = 0;
        for (int i = 0; i < nSpecWvl; i++) {
            float wvl = specWvl[0][i];
            float width = specWvl[1][i];
            int count = 0;
            while (j < nWvl && fullWvl[j] < wvl - width / 2) j++;
            if (j == nWvl) throw new OperatorException("wavelength not found reading surface spectra");
            while (fullWvl[j] < wvl + width / 2) {
                soilSurfSpec[i] += fullSoil[j];
                vegSurfSpec[i] += fullVeg[j];
                count++;
                j++;
            }
            if (j == nWvl) throw new OperatorException("wavelength window exceeds surface spectra range");
            if (count > 0) {
                soilSurfSpec[i] /= count;
                vegSurfSpec[i] /= count;
            }
        }
    }

    private void createMomoLookupTable() throws IOException {
        // todo: Scientists to provide LUTs for S2 MSI !!!
        final String lutInstrument = InstrumentConsts.MSI_INSTRUMENT_NAME;
        ImageInputStream aotIis = Luts.getAotLutData(lutInstrument);
        ImageInputStream gasIis = Luts.getCwvLutData(lutInstrument);
        momo = new MomoLut(aotIis, gasIis, InstrumentConsts.N_LUT_BANDS);
    }

    private float[][] getSpectralWvl(String[] bandNames) {
        float[][] wvl = new float[2][bandNames.length];
        for (int i = 0; i < bandNames.length; i++) {
            wvl[0][i] = sourceProduct.getBand(bandNames[i]).getSpectralWavelength();
            wvl[1][i] = sourceProduct.getBand(bandNames[i]).getSpectralBandwidth();
        }
        return wvl;
    }

    private boolean readAllValues(int x, int y, Map<String, Tile> sourceTiles, double[] tileValues) {
        boolean valid = true;
        for (int i = 0; i < InstrumentConsts.GEOM_NAMES.length; i++) {
            tileValues[i] = sourceTiles.get(InstrumentConsts.GEOM_NAMES[i]).getSampleDouble(x, y);
            valid = valid && (sourceNoDataValues.get(InstrumentConsts.GEOM_NAMES[i]).compareTo(tileValues[i]) != 0);

        }
        int skip = InstrumentConsts.GEOM_NAMES.length;
        for (int i = 0; i < InstrumentConsts.REFLEC_NAMES.length; i++) {
            tileValues[i + skip] = sourceTiles.get(InstrumentConsts.REFLEC_NAMES[i]).getSampleDouble(x, y);
            valid = valid && (sourceNoDataValues.get(InstrumentConsts.REFLEC_NAMES[i]).compareTo(tileValues[i + skip]) != 0);
        }
        skip += InstrumentConsts.REFLEC_NAMES.length;

        // surface pressure data
        tileValues[skip] = sourceTiles.get(InstrumentConsts.SURFACE_PRESSURE_NAME).getSampleDouble(x, y);
        valid = valid && (sourceNoDataValues.get(InstrumentConsts.SURFACE_PRESSURE_NAME).compareTo(tileValues[skip]) != 0);

        // ozone data
        tileValues[skip + 1] = sourceTiles.get(InstrumentConsts.OZONE_NAME).getSampleDouble(x, y);
        valid = valid && (sourceNoDataValues.get(InstrumentConsts.OZONE_NAME).compareTo(tileValues[skip + 1]) != 0);

        return valid;
    }

    private void addToSumArray(double[] tileValues, double[] sumVal) {
        for (int i = 0; i < sumVal.length; i++) {
            sumVal[i] += tileValues[i];
        }
    }

    //
//     verify that ozone column is in DU
//     function assumes 2 common possibilities:
//         1. Ozone column being in DU (generally 100 < ozDU < 1000)
//         2. Ozone column being in atm.cm (generally < 1 )
//     conversion factor is 1000
//     return ozone column in [DU]
//
    private double ensureO3DobsonUnits(double ozoneColumn) {
        return (ozoneColumn < 1) ? ozoneColumn * 1000 : ozoneColumn;
    }

    //      Test whether @validBand contains any valid datapoint in the given source rectangle
    private boolean containsTileValidData(Rectangle srcRec) {
        Tile validTile = getSourceTile(validBand, srcRec);
        for (Tile.Pos pos : validTile) {
            if (validTile.getSampleBoolean(pos.x, pos.y)) {
                return true;
            }
        }
        return false;
    }

    //     Tests uniformity on the given bin pixel (e.g. 9x9 block)
//     based on the NIR reflectance (max - min < 0.2)
    private boolean uniformityTest(Map<String, Tile> sourceTiles, int iX, int iY) {
        String nirName = InstrumentConsts.REFLEC_NAMES[11];  // todo: define
        Guardian.assertNotNullOrEmpty("nirName is empty", nirName);
        double nan = sourceNoDataValues.get(nirName);
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        int xOffset = iX * pixelWindow.width + pixelWindow.x;
        int yOffset = iY * pixelWindow.height + pixelWindow.y;
        for (int y = yOffset; y < yOffset + pixelWindow.height; y++) {
            for (int x = xOffset; x < xOffset + pixelWindow.width; x++) {
                boolean valid = sourceTiles.get(validName).getSampleBoolean(x, y);
                double value = sourceTiles.get(nirName).getSampleDouble(x, y);
                if (valid && Double.compare(nan, value) != 0) {
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            }
        }
        return ((max - min) < 20);
    }

    private float[] getLatLon(int iX, int iY, Rectangle pixelWindow, Product sourceProduct) {
        float xOffset = ((iX + 0.5f) * pixelWindow.width + pixelWindow.x);
        float yOffset = ((iY + 0.5f) * pixelWindow.height + pixelWindow.y);
        GeoCoding geoCoding = sourceProduct.getSceneGeoCoding();
        GeoPos geoPos = geoCoding.getGeoPos(new PixelPos(xOffset, yOffset), null);
        return new float[]{(float) geoPos.lat, (float) geoPos.lon};
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(S2AerosolOp.class);
        }
    }
}
