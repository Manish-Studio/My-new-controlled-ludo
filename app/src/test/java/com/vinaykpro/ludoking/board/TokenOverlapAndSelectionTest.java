package com.vinaykpro.ludoking.board;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TokenOverlapAndSelectionTest {

    private static TokenTouchResolver.TokenCandidate makeCandidate(
            int id,
            String color,
            int cell,
            boolean alive,
            boolean eligible,
            boolean activeHuman,
            float cx,
            float cy,
            float size,
            float elevation
    ) {
        float[] bounds = TokenTouchResolver.computeExpandedHitBounds(cx, cy, size, size, size, size);
        return new TokenTouchResolver.TokenCandidate(
                id, color, cell, alive, eligible, activeHuman,
                cx, cy, bounds[0], bounds[1], bounds[2], bounds[3], elevation
        );
    }

    @Test
    public void twoDifferentPlayersOverlap_activeEligiblePrioritizedOnTopAndSelectedEvenWhenTappingOpponentArea() {
        List<TokenOverlapLayoutHelper.TokenState> states = Arrays.asList(
                new TokenOverlapLayoutHelper.TokenState(0, "red", false, false),
                new TokenOverlapLayoutHelper.TokenState(1, "blue", true, true)
        );
        List<TokenOverlapLayoutHelper.SlotPlacement> placements =
                TokenOverlapLayoutHelper.computeCellPlacements(states, 8, 200f, 300f, 70f, false);

        assertEquals(2, placements.size());
        TokenOverlapLayoutHelper.SlotPlacement opponentPlacement = placements.get(0);
        TokenOverlapLayoutHelper.SlotPlacement activePlacement = placements.get(1);
        assertEquals(0, opponentPlacement.tokenId);
        assertEquals(1, activePlacement.tokenId);
        assertTrue("Active eligible token must have higher Z-elevation", activePlacement.elevation > opponentPlacement.elevation);

        // Even if tap hits closer to opponent visual offset center, active eligible token in same cell is selected
        List<TokenTouchResolver.TokenCandidate> candidates = Arrays.asList(
                makeCandidate(0, "red", 8, true, false, false,
                        opponentPlacement.translationX + 35f, opponentPlacement.translationY + 55f, 54f, 100f),
                makeCandidate(1, "blue", 8, true, true, true,
                        activePlacement.translationX + 35f, activePlacement.translationY + 55f, 54f, activePlacement.elevation)
        );
        List<TokenTouchResolver.CellBounds> cells = Collections.singletonList(
                new TokenTouchResolver.CellBounds(8, 185f, 285f, 285f, 385f)
        );

        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(
                opponentPlacement.translationX + 35f, opponentPlacement.translationY + 55f,
                candidates, cells, false);

        assertEquals(TokenTouchResolver.ActionType.SELECT_TOKEN_TO_MOVE, res.actionType);
        assertNotNull(res.selectedToken);
        assertEquals(1, res.selectedToken.id);
        assertEquals("blue", res.selectedToken.color);
    }

    @Test
    public void threeDifferentPlayersOverlap_selectsActiveEligibleTokenNeverOpponents() {
        List<TokenOverlapLayoutHelper.TokenState> states = Arrays.asList(
                new TokenOverlapLayoutHelper.TokenState(0, "red", false, false),
                new TokenOverlapLayoutHelper.TokenState(1, "green", true, true),
                new TokenOverlapLayoutHelper.TokenState(2, "yellow", false, false)
        );
        List<TokenOverlapLayoutHelper.SlotPlacement> placements =
                TokenOverlapLayoutHelper.computeCellPlacements(states, 21, 400f, 500f, 72f, true);

        assertEquals(3, placements.size());
        // Last slot must hold green (tokenId 1)
        assertEquals(1, placements.get(2).tokenId);

        List<TokenTouchResolver.TokenCandidate> candidates = new ArrayList<>();
        for (TokenOverlapLayoutHelper.SlotPlacement p : placements) {
            boolean isGreen = p.tokenId == 1;
            candidates.add(makeCandidate(
                    p.tokenId,
                    isGreen ? "green" : (p.tokenId == 0 ? "red" : "yellow"),
                    21, true, isGreen, isGreen,
                    p.translationX + 36f, p.translationY + 54f, 52f, p.elevation
            ));
        }

        // Tap directly over opponent yellow's offset center (20px away from cell edge)
        TokenTouchResolver.TokenCandidate yellow = candidates.get(1);
        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(
                yellow.centerX - 18f, yellow.centerY - 12f,
                candidates,
                Collections.singletonList(new TokenTouchResolver.CellBounds(21, 380f, 480f, 480f, 580f)),
                false
        );
        assertEquals(TokenTouchResolver.ActionType.SELECT_TOKEN_TO_MOVE, res.actionType);
        assertNotNull(res.selectedToken);
        assertEquals("green", res.selectedToken.color);
    }

    @Test
    public void fourDifferentPlayersOverlap_fannedClusterRemainsCompactAndSelectsActiveHuman() {
        List<TokenOverlapLayoutHelper.TokenState> states = Arrays.asList(
                new TokenOverlapLayoutHelper.TokenState(0, "red", false, false),
                new TokenOverlapLayoutHelper.TokenState(1, "green", false, false),
                new TokenOverlapLayoutHelper.TokenState(2, "yellow", false, false),
                new TokenOverlapLayoutHelper.TokenState(3, "blue", true, true)
        );
        float blockSize = 72f;
        List<TokenOverlapLayoutHelper.SlotPlacement> placements =
                TokenOverlapLayoutHelper.computeCellPlacements(states, 34, 300f, 300f, blockSize, false);

        assertEquals(4, placements.size());
        float blueElev = -1f;
        float maxOpponentElev = -1f;
        for (TokenOverlapLayoutHelper.SlotPlacement p : placements) {
            assertTrue("Offset dx must fit comfortably inside cell", Math.abs(p.translationX - 300f) < blockSize * 0.4f);
            assertTrue("Offset dy must fit comfortably inside cell", Math.abs(p.translationY - 300f) < blockSize * 0.4f);
            if (p.tokenId == 3) {
                blueElev = p.elevation;
            } else {
                maxOpponentElev = Math.max(maxOpponentElev, p.elevation);
            }
        }
        assertTrue(blueElev > maxOpponentElev);

        // Even if tap falls strictly on red's quadrant in the 2x2 cell, cell fallback routes to eligible blue token
        List<TokenTouchResolver.TokenCandidate> candidates = Arrays.asList(
                makeCandidate(0, "red", 34, true, false, false, 285f, 285f, 48f, 34f),
                makeCandidate(1, "green", 34, true, false, false, 315f, 285f, 48f, 36f),
                makeCandidate(2, "yellow", 34, true, false, false, 285f, 315f, 48f, 38f),
                makeCandidate(3, "blue", 34, true, true, true, 355f, 355f, 48f, 120f)
        );

        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(
                282f, 282f,
                candidates,
                Collections.singletonList(new TokenTouchResolver.CellBounds(34, 270f, 270f, 370f, 370f)),
                false
        );
        assertEquals(TokenTouchResolver.ActionType.SELECT_TOKEN_TO_MOVE, res.actionType);
        assertEquals(3, res.selectedToken.id);
    }

    @Test
    public void multipleActiveTokensStacked_selectsClosestActiveTokenToTapPoint() {
        List<TokenTouchResolver.TokenCandidate> candidates = Arrays.asList(
                makeCandidate(0, "red", 13, true, true, true, 280f, 300f, 52f, 114f),
                makeCandidate(1, "red", 13, true, true, true, 320f, 300f, 52f, 116f),
                makeCandidate(2, "green", 13, true, false, false, 300f, 315f, 52f, 13f)
        );

        TokenTouchResolver.ResolutionResult tapLeft = TokenTouchResolver.resolveTap(
                274f, 300f, candidates, Collections.emptyList(), false);
        assertEquals(0, tapLeft.selectedToken.id);

        TokenTouchResolver.ResolutionResult tapRight = TokenTouchResolver.resolveTap(
                326f, 300f, candidates, Collections.emptyList(), false);
        assertEquals(1, tapRight.selectedToken.id);
    }

    @Test
    public void opponentOnlyCellTap_returnsNoneAndDoesNotSelectOpponent() {
        List<TokenTouchResolver.TokenCandidate> candidates = Arrays.asList(
                makeCandidate(0, "red", 8, true, false, false, 200f, 200f, 60f, 8f),
                makeCandidate(1, "blue", 25, true, true, true, 500f, 500f, 60f, 105f)
        );
        List<TokenTouchResolver.CellBounds> cells = Arrays.asList(
                new TokenTouchResolver.CellBounds(8, 175f, 175f, 235f, 235f),
                new TokenTouchResolver.CellBounds(25, 475f, 475f, 535f, 535f)
        );

        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(200f, 200f, candidates, cells, false);
        assertEquals(TokenTouchResolver.ActionType.NONE, res.actionType);
        assertNull(res.selectedToken);
    }

    @Test
    public void protrudingTopEdgeTokenTap_stillHitsExpandedBounds() {
        // Row-0 tokens lifted upward can sit slightly at y < 0 in relativeLayout space
        List<TokenTouchResolver.TokenCandidate> candidates = Collections.singletonList(
                makeCandidate(0, "yellow", 24, true, true, true, 480f, -8f, 60f, 107f)
        );
        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(
                480f, -15f, candidates, Collections.emptyList(), false);
        assertEquals(TokenTouchResolver.ActionType.SELECT_TOKEN_TO_MOVE, res.actionType);
        assertEquals(0, res.selectedToken.id);
    }

    @Test
    public void cheatingPreRollFiveLock_takesPriorityOverMoveOnAnyVisibleToken() {
        List<TokenTouchResolver.TokenCandidate> candidates = Arrays.asList(
                makeCandidate(0, "red", 8, true, false, false, 200f, 200f, 60f, 8f),
                makeCandidate(1, "blue", 25, true, true, true, 500f, 500f, 60f, 105f)
        );
        TokenTouchResolver.ResolutionResult res = TokenTouchResolver.resolveTap(
                200f, 200f, candidates, Collections.emptyList(), true);
        assertEquals(TokenTouchResolver.ActionType.CHEAT_LOCK_FIVE, res.actionType);
        assertNotNull(res.selectedToken);
        assertEquals(0, res.selectedToken.id);
    }
}
