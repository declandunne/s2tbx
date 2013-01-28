package org.esa.beam.dataio.s3.synergy;/*
 * Copyright (C) 2012 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.MultiLevelModel;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;

import javax.media.jai.Interpolation;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.ScaleDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;

class ImageMosaic {

    private static final Interpolation INTERPOLATION = Interpolation.getInstance(Interpolation.INTERP_NEAREST);

    public static RenderedImage create(RenderedImage... sourceImages) {
        for (int i = 1, t = 0; i < sourceImages.length; i++) {
            t += sourceImages[i - 1].getWidth();
            sourceImages[i] = TranslateDescriptor.create(sourceImages[i], (float) t, 0.0f, INTERPOLATION, null);
        }

        return MosaicDescriptor.create(sourceImages, MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                       null, null, null, null, null);
    }

    public static MultiLevelImage create(final MultiLevelImage... sourceImages) {
        final int mosaicWidth = mosaicWidth(sourceImages);
        final int mosaicHeight = sourceImages[0].getHeight();
        final MultiLevelModel model = new DefaultMultiLevelModel(sourceImages[0].getModel().getLevelCount(),
                                                                 new AffineTransform(),
                                                                 mosaicWidth,
                                                                 mosaicHeight);

        final AbstractMultiLevelSource mosaicMultiLevelSource = new AbstractMultiLevelSource(model) {

            @Override
            protected RenderedImage createImage(int level) {
                final RenderedImage[] levelImages = new RenderedImage[sourceImages.length];
                for (int i = 0; i < levelImages.length; i++) {
                    levelImages[i] = sourceImages[i].getImage(level);
                }
                RenderedImage mosaicImage = create(levelImages);
                final float expectedLevelImageWidth = (float) (mosaicWidth / model.getScale(level));
                if ((int) expectedLevelImageWidth != mosaicImage.getWidth()) {
                    final float scale = expectedLevelImageWidth / mosaicImage.getWidth();
                    mosaicImage = ScaleDescriptor.create(mosaicImage, scale, 1.0f, 0.0f, 0.0f, INTERPOLATION, null);
                }

                return mosaicImage;
            }
        };

        return new DefaultMultiLevelImage(mosaicMultiLevelSource);
    }

    private static int mosaicWidth(MultiLevelImage[] sourceImages) {
        int w = 0;
        for (final MultiLevelImage sourceImage : sourceImages) {
            w += sourceImage.getWidth();
        }
        return w;
    }
}
