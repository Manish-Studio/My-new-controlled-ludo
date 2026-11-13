package com.vinaykpro.ludoking;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * Computes and applies exact player-box-centered geometry for the winning crown views
 * across all four corner player yards and all screen dimensions/orientations.
 */
public final class WinnerCrownLayoutHelper {

    public static final float PLAYER_BOX_RATIO = 0.40f;
    public static final float CROWN_VISUAL_SCALE = 0.72f;
    public static final float NUMBER_OFFSET_Y_RATIO = 0.08f;

    public static final class CrownBoxGeometry {
        public final int boxLeft;
        public final int boxTop;
        public final int boxSize;
        public final float crownCenterX;
        public final float crownCenterY;
        public final float numberOffsetY;

        public CrownBoxGeometry(int boxLeft, int boxTop, int boxSize, float crownCenterX, float crownCenterY, float numberOffsetY) {
            this.boxLeft = boxLeft;
            this.boxTop = boxTop;
            this.boxSize = boxSize;
            this.crownCenterX = crownCenterX;
            this.crownCenterY = crownCenterY;
            this.numberOffsetY = numberOffsetY;
        }
    }

    private WinnerCrownLayoutHelper() {}

    /**
     * Computes exact bounds and visual center inside the player's board corner box.
     *
     * @param boardWidth  Width of the square Ludo board container.
     * @param boardHeight Height of the square Ludo board container.
     * @param crownSlot   1 = Bottom-Left, 2 = Top-Left, 3 = Top-Right, 4 = Bottom-Right.
     */
    @NonNull
    public static CrownBoxGeometry computeCrownGeometry(int boardWidth, int boardHeight, int crownSlot) {
        int squareSize = Math.max(0, Math.min(boardWidth, boardHeight));
        int boxSize = Math.round(squareSize * PLAYER_BOX_RATIO);

        int boxLeft;
        int boxTop;
        switch (crownSlot) {
            case 2: // Top-Left
                boxLeft = 0;
                boxTop = 0;
                break;
            case 3: // Top-Right
                boxLeft = Math.max(0, boardWidth - boxSize);
                boxTop = 0;
                break;
            case 4: // Bottom-Right
                boxLeft = Math.max(0, boardWidth - boxSize);
                boxTop = Math.max(0, boardHeight - boxSize);
                break;
            case 1: // Bottom-Left
            default:
                boxLeft = 0;
                boxTop = Math.max(0, boardHeight - boxSize);
                break;
        }

        float centerX = boxLeft + (boxSize / 2.0f);
        float centerY = boxTop + (boxSize / 2.0f);
        float numberOffsetY = boxSize * NUMBER_OFFSET_Y_RATIO;
        return new CrownBoxGeometry(boxLeft, boxTop, boxSize, centerX, centerY, numberOffsetY);
    }

    /**
     * Applies exact box dimensions, zero hardcoded margins, and center-aligned scaling
     * to a winning crown view and its rank number badge relative to the board container.
     */
    public static void applyCrownLayout(
            @Nullable ImageView crownView,
            @Nullable ImageView numberBadgeView,
            int boardContainerId,
            int boardSizePx,
            int crownSlot
    ) {
        if (crownView == null || boardSizePx <= 0) return;

        CrownBoxGeometry geom = computeCrownGeometry(boardSizePx, boardSizePx, crownSlot);
        if (crownView.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) crownView.getLayoutParams();
            lp.width = geom.boxSize;
            lp.height = geom.boxSize;
            lp.matchConstraintPercentWidth = 1.0f;
            lp.setMargins(0, 0, 0, 0);
            lp.setMarginStart(0);
            lp.setMarginEnd(0);

            if (crownSlot == 2 || crownSlot == 3) {
                lp.topToTop = boardContainerId;
                lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
            } else {
                lp.bottomToBottom = boardContainerId;
                lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            }

            if (crownSlot == 1 || crownSlot == 2) {
                lp.startToStart = boardContainerId;
                lp.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            } else {
                lp.endToEnd = boardContainerId;
                lp.startToStart = ConstraintLayout.LayoutParams.UNSET;
            }

            crownView.setLayoutParams(lp);
        }

        crownView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crownView.setPadding(0, 0, 0, 0);
        crownView.setTranslationX(0f);
        crownView.setTranslationY(0f);
        crownView.setPivotX(geom.boxSize / 2.0f);
        crownView.setPivotY(geom.boxSize / 2.0f);
        crownView.setScaleX(CROWN_VISUAL_SCALE);
        crownView.setScaleY(CROWN_VISUAL_SCALE);

        if (numberBadgeView != null) {
            if (numberBadgeView.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams nlp = (ConstraintLayout.LayoutParams) numberBadgeView.getLayoutParams();
                int badgeSize = Math.max(1, Math.round(geom.boxSize * 0.22f));
                nlp.width = badgeSize;
                nlp.height = badgeSize;
                nlp.setMargins(0, 0, 0, 0);
                nlp.topToTop = crownView.getId();
                nlp.bottomToBottom = crownView.getId();
                nlp.startToStart = crownView.getId();
                nlp.endToEnd = crownView.getId();
                numberBadgeView.setLayoutParams(nlp);
            }
            numberBadgeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            numberBadgeView.setTranslationX(0f);
            numberBadgeView.setTranslationY(geom.numberOffsetY);
        }
    }
}
