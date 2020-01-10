package org.esa.s2tbx.dataio.s2.l1b.tiles;

import com.bc.ceres.glevel.support.DefaultMultiLevelSource;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.esa.s2tbx.dataio.jp2.TileLayout;
import org.esa.s2tbx.dataio.s2.S2Config;
import org.esa.s2tbx.dataio.s2.S2IndexBandInformation;
import org.esa.s2tbx.dataio.s2.S2SpatialResolution;
import org.esa.s2tbx.dataio.s2.l1b.L1bSceneDescription;
import org.esa.s2tbx.dataio.s2.l1b.Sentinel2L1BProductReader;
import org.esa.s2tbx.dataio.s2.l1b.filepaterns.S2L1BGranuleDirFilename;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.image.AbstractMatrixMosaicSubsetMultiLevelSource;
import org.esa.snap.core.image.MosaicMatrix;
import org.esa.snap.core.image.UncompressedTileOpImageCallback;
import org.esa.snap.core.util.ImageUtils;
import org.esa.snap.core.util.SystemUtils;

import javax.media.jai.*;
import javax.media.jai.operator.BorderDescriptor;
import javax.media.jai.operator.ConstantDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Created by jcoravu on 9/1/2020.
 */
public class TileIndexMultiLevelSource extends AbstractMatrixMosaicSubsetMultiLevelSource implements UncompressedTileOpImageCallback<TileIndexBandMatrixCell> {

    public TileIndexMultiLevelSource(int levelCount, MosaicMatrix mosaicMatrix, Rectangle imageMatrixReadBounds, AffineTransform imageToModelTransform) {
        super(levelCount, mosaicMatrix, imageMatrixReadBounds, null, imageToModelTransform);
    }

    @Override
    public PlanarImage buildTileOpImage(Rectangle imageCellReadBounds, int level, Point tileOffset, Dimension tileSize, TileIndexBandMatrixCell matrixCell) {
        int levelImageTileWidth = ImageUtils.computeLevelSize(tileSize.width, level);
        int levelImageTileHeight = ImageUtils.computeLevelSize(tileSize.height, level);
        return ConstantDescriptor.create((float)levelImageTileWidth, (float)levelImageTileHeight, new Short[]{matrixCell.getBandValue()}, null);
    }

    @Override
    protected java.util.List<RenderedImage> buildMatrixCellTileImages(int level, Rectangle imageCellReadBounds, float cellTranslateLevelOffsetX, float cellTranslateLevelOffsetY,
                                                                      MosaicMatrix.MatrixCell matrixCell) {

        TileIndexBandMatrixCell tileIndexBandMatrixCell = (TileIndexBandMatrixCell)matrixCell;
        Dimension uncompressedTileSize = new Dimension(imageCellReadBounds.width, imageCellReadBounds.height);
        return buildUncompressedTileImages(level, imageCellReadBounds, uncompressedTileSize, cellTranslateLevelOffsetX, cellTranslateLevelOffsetY, this, tileIndexBandMatrixCell);
    }

    @Override
    protected double[] getMosaicOpBackgroundValues() {
        return new double[]{S2Config.FILL_CODE_MOSAIC_BG};
    }

    @Override
    protected double[][] getMosaicOpSourceThreshold() {
        return new double[][]{{1.0}};
    }
}