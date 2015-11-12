/*
* Copyright  2015 Google Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.google.blockly.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.google.blockly.R;
import com.google.blockly.control.ConnectionManager;
import com.google.blockly.model.Block;
import com.google.blockly.model.Connection;
import com.google.blockly.model.Input;
import com.google.blockly.model.WorkspacePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a block and handles laying out all its inputs/fields.
 * <p/>
 * Known issues:
 * <ul>
 *     <li>With inline inputs, blocks connected to Statement inputs take too much vertical space by
 *     the amount ot vertical field padding.</li>
 *     <li>Connector positions are not correct in RtL mode (this issue was also present in
 *     path-based rendering).</li>
 *     <li>Bottom of Statement connector is overdrawn by block boundary if it is the last input in
 *     the block.</li>
 *     <li>Connector or block highlighting is not implemented. Related, support for drawing over
 *     neighbouring blocks is not yet implemented (this is needed for highlighting in the style of
 *     Web Blockly).</li>
 * </ul>
 */
public class BlockView extends FrameLayout {
    private static final String TAG = "BlockView";

    // TODO: Replace these with dimens so they get scaled correctly
    // Minimum width of a block should be the same as an empty field.
    private static final int MIN_WIDTH = InputView.MIN_WIDTH;

    private final WorkspaceHelper mHelper;
    private final WorkspaceHelper.BlockTouchHandler mTouchHandler;
    private final Block mBlock;
    private final ConnectionManager mConnectionManager;

    // Child views for the block inputs and their children.
    private final ArrayList<InputView> mInputViews = new ArrayList<>();
    private int mInputCount;

    // Reference points for connectors relative to this view (needed for selective highlighting).
    private final ViewPoint mOutputConnectorOffset = new ViewPoint();
    private final ViewPoint mPreviousConnectorOffset = new ViewPoint();
    private final ViewPoint mNextConnectorOffset = new ViewPoint();
    private final ArrayList<ViewPoint> mInputConnectorOffsets = new ArrayList<>();

    // Current measured size of this block view.
    private final ViewPoint mBlockViewSize = new ViewPoint();
    // Position of the connection currently being updated, for temporary use during
    // layoutPatchesAndConnectors.
    private final ViewPoint mTempConnectionPosition = new ViewPoint();
    // Layout coordinates for inputs in this Block, so they don't have to be computed repeatedly.
    private final ArrayList<ViewPoint> mInputLayoutOrigins = new ArrayList<>();
    // List of widths of multi-field rows when rendering inline inputs.
    private final ArrayList<Integer> mInlineRowWidth = new ArrayList<>();
    private final WorkspacePoint mTempWorkspacePoint = new WorkspacePoint();
    // Fields for highlighting.
    private boolean mHighlightBlock;
    private Connection mHighlightConnection;
    // Offset of the block origin inside the view's measured area.
    private int mLayoutMarginLeft;
    private int mMaxStatementFieldsWidth;
    // Vertical offset for positioning the "Next" block (if one exists).
    private int mNextBlockVerticalOffset;
    // Width  and height of the block "content", i.e., all its input fields. Unlike the view size,
    // this does not include extruding connectors (e.g., Output, Next) and connected input blocks.
    private int mBlockContentWidth;
    private int mBlockContentHeight;

    // Objects for drawing the block.
    private final PatchManager mPatchManager;
    private final ArrayList<Drawable> mBlockPatches = new ArrayList<>();
    private final ArrayList<Rect> mFillRects = new ArrayList<>();
    private Rect mNextFillRect = null;
    private ColorFilter mBlockColorFilter;
    private final Paint mFillPaint = new Paint();

    // Flag is set to true if this block has at least one "Value" input.
    private boolean mHasValueInput = false;

    /**
     * Create a new BlockView for the given block using the workspace's style. This constructor is
     * for non-interactive display blocks. If this block is part of a {@link
     * com.google.blockly.model.Workspace}, then {@link BlockView(Context, int, Block,
     * WorkspaceHelper, BlockGroup, View.OnTouchListener)} should be used instead.
     *
     * @param context The context for creating this view.
     * @param block The {@link Block} represented by this view.
     * @param helper The helper for loading workspace configs and doing calculations.
     * @param parentGroup The {@link BlockGroup} this view will live in.
     * @param connectionManager The {@link ConnectionManager} to update when moving connections.
     * @param touchHandler The {@link WorkspaceHelper.BlockTouchHandler} to call when the block is
     * touched.
     */
    public BlockView(Context context, Block block, WorkspaceHelper helper, BlockGroup parentGroup,
            ConnectionManager connectionManager, WorkspaceHelper.BlockTouchHandler touchHandler) {
        this(context, 0 /* default style */, block, helper, parentGroup, connectionManager,
                touchHandler);
    }

    /**
     * Create a new BlockView for the given block using the specified style. The style must extend
     * {@link R.style#DefaultBlockStyle}.
     *
     * @param context The context for creating this view.
     * @param blockStyle The resource id for the style to use on this view.
     * @param block The {@link Block} represented by this view.
     * @param helper The helper for loading workspace configs and doing calculations.
     * @param parentGroup The {@link BlockGroup} this view will live in.
     * @param connectionManager The {@link ConnectionManager} to update when moving connections.
     * @param touchHandler The handler for forwarding touch events on this block to the
     * {@link WorkspaceHelper}.
     */
    public BlockView(Context context, int blockStyle, Block block, WorkspaceHelper helper,
            BlockGroup parentGroup, ConnectionManager connectionManager,
            WorkspaceHelper.BlockTouchHandler touchHandler) {
        super(context, null, 0);

        mBlock = block;
        mConnectionManager = connectionManager;
        mHelper = helper;
        mTouchHandler = touchHandler;
        mPatchManager = mHelper.getPatchManager();  // Shortcut.

        if (parentGroup != null) {
            parentGroup.addView(this);
        }
        block.setView(this);

        setWillNotDraw(false);

        initViews(context, blockStyle, parentGroup);
        initDrawingObjects();
    }

    /**
     * Select a connection for highlighted drawing.
     *
     * @param connection The connection whose port to highlight. This must be a connection
     * associated with the {@link Block} represented by this {@link BlockView}
     * instance.
     */
    public void setHighlightConnection(Connection connection) {
        mHighlightBlock = false;
        mHighlightConnection = connection;
        invalidate();
    }

    /**
     * Set highlighting of the entire block, including all inline Value input ports.
     */
    public void setHighlightEntireBlock() {
        mHighlightBlock = true;
        mHighlightConnection = null;
        invalidate();
    }

    /**
     * Clear all highlighting and return everything to normal rendering.
     */
    public void clearHighlight() {
        mHighlightBlock = false;
        mHighlightConnection = null;
        invalidate();
    }

    /**
     * Test whether event hits visible parts of this block and notify {@link WorkspaceView} if it
     * does.
     *
     * @param event The {@link MotionEvent} to handle.
     *
     * @return False if the touch was on the view but not on a visible part of the block; otherwise
     * returns whether the {@link WorkspaceView} says that the event is being handled properly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return hitTest(event) && mTouchHandler.onTouchBlock(this, event);
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int i = 0; i < mFillRects.size(); ++i) {
            c.drawRect(mFillRects.get(i), mFillPaint);
        }

        for (int i = 0; i < mBlockPatches.size(); ++i) {
            mBlockPatches.get(i).draw(c);
        }

        drawConnectorCenters(c);
        drawHighlights(c);
    }

    /**
     * Measure all children (i.e., block inputs) and compute their sizes and relative positions
     * for use in {@link #onLayout}.
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getBlock().getInputsInline()) {
            measureInlineInputs(widthMeasureSpec, heightMeasureSpec);
        } else {
            measureExternalInputs(widthMeasureSpec, heightMeasureSpec);
        }

        mNextBlockVerticalOffset = mBlockContentHeight;
        mBlockViewSize.y = mBlockContentHeight;
        if (mBlock.getNextConnection() != null) {
            mBlockViewSize.y += mPatchManager.mNextConnectorHeight;
        }

        if (mBlock.getOutputConnection() != null) {
            mLayoutMarginLeft = mPatchManager.mOutputConnectorWidth;
            mBlockViewSize.x += mLayoutMarginLeft;
        } else {
            mLayoutMarginLeft = 0;
        }

        setMeasuredDimension(mBlockViewSize.x, mBlockViewSize.y);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Note that layout must be done regardless of the value of the "changed" parameter.
        boolean rtl = mHelper.useRtL();
        int rtlSign = rtl ? -1 : +1;

        int xFrom = mLayoutMarginLeft;
        if (rtl) {
            xFrom = mBlockViewSize.x - xFrom;
        }

        for (int i = 0; i < mInputViews.size(); i++) {
            int rowTop = mInputLayoutOrigins.get(i).y;

            InputView inputView = mInputViews.get(i);
            int inputViewWidth = inputView.getMeasuredWidth();
            int rowFrom = xFrom + rtlSign * mInputLayoutOrigins.get(i).x;
            if (rtl) {
                rowFrom -= inputViewWidth;
            }

            inputView.layout(rowFrom, rowTop, rowFrom + inputViewWidth,
                    rowTop + inputView.getMeasuredHeight());
        }

        layoutPatchesAndConnectors();
        updateConnectorLocations();
    }

    /**
     * @return The block for this view.
     */
    public Block getBlock() {
        return mBlock;
    }

    /**
     * Update the position of the block in workspace coordinates based on the view's location.
     */
    private void updateBlockPosition() {
        // Only update the block position if it isn't a top level block.
        if (mBlock.getPreviousBlock() != null
                || (mBlock.getOutputConnection() != null
                && mBlock.getOutputConnection().getTargetBlock() != null)) {
            mHelper.getWorkspaceCoordinates(this, mTempWorkspacePoint);
            mBlock.setPosition(mTempWorkspacePoint.x, mTempWorkspacePoint.y);
        }
    }

    /**
     * Test whether a {@link MotionEvent} event is (approximately) hitting a visible part of this
     * view.
     * <p/>
     * This is used to determine whether the event should be handled by this view, e.g., to activate
     * dragging or to open a context menu. Since the actual block interactions are implemented at
     * the {@link WorkspaceView} level, there is no need to store the event data in this class.
     *
     * @param event The {@link MotionEvent} to check.
     *
     * @return True if the coordinate of the motion event is on the visible, non-transparent part of
     * this view; false otherwise.
     */
    private boolean hitTest(MotionEvent event) {
        final int eventX = (int) event.getX();
        final int eventY = (int) event.getY();

        // Do the exact same thing for RTL and LTR, with reversed left and right block bounds. Note
        // that the bounds of each InputView include any connected child blocks, so in RTL mode,
        // the left-hand side of the input fields must be obtained from the right-hand side of the
        // input and the field layout width.
        if (mHelper.useRtL()) {
            // First check whether event is in the general horizontal range of the block outline
            // (minus children) and exit if it is not.
            final int blockEnd = mBlockViewSize.x - mLayoutMarginLeft;
            final int blockBegin = blockEnd - mBlockContentWidth;
            if (eventX < blockBegin || eventX > blockEnd) {
                return false;
            }

            // In the ballpark - now check whether event is on a field of any of this block's
            // inputs. If it is, then the event belongs to this BlockView, otherwise it does not.
            for (int i = 0; i < mInputViews.size(); ++i) {
                final InputView inputView = mInputViews.get(i);
                if (inputView.isOnFields(
                        eventX - (inputView.getRight() - inputView.getFieldLayoutWidth()),
                        eventY - inputView.getTop())) {
                    return true;
                }
            }
        } else {
            final int blockBegin = mLayoutMarginLeft;
            final int blockEnd = mBlockContentWidth;
            if (eventX < blockBegin || eventX > blockEnd) {
                return false;
            }

            for (int i = 0; i < mInputViews.size(); ++i) {
                final InputView inputView = mInputViews.get(i);
                if (inputView.isOnFields(
                        eventX - inputView.getLeft(), eventY - inputView.getTop())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Draw highlights of block-level connections, or the entire block, if necessary.
     *
     * @param c The canvas to draw on.
     */
    private void drawHighlights(Canvas c) {
        if (mHighlightBlock) {
            // Draw entire block highlighted..
        } else if (mHighlightConnection != null) {
            int rtlSign = mHelper.useRtL() ? -1 : +1;
            if (mHighlightConnection == mBlock.getOutputConnection()) {
            } else if (mHighlightConnection == mBlock.getPreviousConnection()) {
            } else if (mHighlightConnection == mBlock.getNextConnection()) {
            } else {
                // If the connection to highlight is not one of the three block-level connectors,
                // then it must be one of the inputs (either a "Next" connector for a Statement or
                // "Input" connector for a Value input). Figure out which input the connection
                // belongs to.
                final Input input = mHighlightConnection.getInput();
                for (int i = 0; i < mInputViews.size(); ++i) {
                    if (mInputViews.get(i).getInput() == input) {
                        final ViewPoint offset = mInputConnectorOffsets.get(i);
                        if (input.getType() == Input.TYPE_STATEMENT) {
                        } else {
                        }
                        break;  // Break out of loop once connection has been found.
                    }
                }
            }
        }
    }

    /**
     * Measure view and its children with inline inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block.</li>
     * </ol>
     * </p>
     */
    private void measureInlineInputs(int widthMeasureSpec, int heightMeasureSpec) {
        int inputViewsSize = mInputViews.size();

        // First pass - measure all fields and inputs; compute maximum width of fields and children
        // over all Statement inputs.
        mMaxStatementFieldsWidth = 0;
        int maxStatementChildWidth = 0;
        for (int i = 0; i < inputViewsSize; i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                mMaxStatementFieldsWidth =
                        Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());
                maxStatementChildWidth =
                        Math.max(maxStatementChildWidth, inputView.getTotalChildWidth());
            }
        }

        // Second pass - compute layout positions and sizes of all inputs.
        int rowLeft = 0;
        int rowTop = 0;

        int rowHeight = 0;
        int maxRowWidth = 0;

        mInlineRowWidth.clear();
        for (int i = 0; i < inputViewsSize; i++) {
            InputView inputView = mInputViews.get(i);

            // If this is a Statement input, force its field width to be the maximum over all
            // Statements, and begin a new layout row.
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // Force all Statement inputs to have the same field width.
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);

                // New row BEFORE each Statement input.
                mInlineRowWidth.add(Math.max(rowLeft,
                        mMaxStatementFieldsWidth + mPatchManager.mStatementInputIndent));

                rowTop += rowHeight;
                rowHeight = 0;
                rowLeft = 0;
            }

            mInputLayoutOrigins.get(i).set(rowLeft, rowTop);

            // Measure input view and update row height and width accordingly.
            inputView.measure(widthMeasureSpec, heightMeasureSpec);
            rowHeight = Math.max(rowHeight, inputView.getMeasuredHeight());

            // Set row height for the current input view as maximum over all views in this row so
            // far. A separate, reverse loop below propagates the maximum to earlier inputs in the
            // same row.
            inputView.setRowHeight(rowHeight);

            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // The block width is that of the widest row.
                maxRowWidth = Math.max(maxRowWidth, inputView.getMeasuredWidth());

                // New row AFTER each Statement input.
                rowTop += rowHeight;
                rowLeft = 0;
                rowHeight = 0;
            } else {
                // For Dummy and Value inputs, row width accumulates. Update maximum width
                // accordingly.
                rowLeft += inputView.getMeasuredWidth();
                maxRowWidth = Math.max(maxRowWidth, rowLeft);
            }
        }

        // Add height of final row. This is non-zero with inline inputs if the final input in the
        // block is not a Statement input.
        rowTop += rowHeight;

        // Third pass - propagate row height maximums backwards. Reset height whenever a Statement
        // input is encoutered.
        int maxRowHeight = 0;
        for (int i = inputViewsSize; i > 0; --i) {
            InputView inputView = mInputViews.get(i - 1);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                maxRowHeight = 0;
            } else {
                maxRowHeight = Math.max(maxRowHeight, inputView.getRowHeight());
                inputView.setRowHeight(maxRowHeight);
            }
        }

        // If there was at least one Statement input, make sure block is wide enough to fit at least
        // an empty Statement connector. If there were non-empty Statement connectors, they were
        // already taken care of in the loop above.
        if (mMaxStatementFieldsWidth > 0) {
            maxRowWidth = Math.max(maxRowWidth,
                    mMaxStatementFieldsWidth + mPatchManager.mStatementInputIndent);
        }

        // Push width of last input row.
        mInlineRowWidth.add(Math.max(rowLeft,
                mMaxStatementFieldsWidth + mPatchManager.mStatementInputIndent));

        // Block width is the computed width of the widest input row, and at least MIN_WIDTH.
        mBlockContentWidth = Math.max(MIN_WIDTH, maxRowWidth);
        mBlockViewSize.x = mBlockContentWidth;

        // View width is the computed width of the widest statement input, including child blocks
        // and padding, and at least the width of the widest input row.
        mBlockViewSize.x = Math.max(mBlockContentWidth,
                mMaxStatementFieldsWidth + maxStatementChildWidth +
                        mPatchManager.mBlockLeftPadding + mPatchManager.mStatementInputPadding);

        // Height is vertical position of next (non-existent) inputs row, and at least MIN_HEIGHT.
        mBlockContentHeight = Math.max(mPatchManager.mMinBlockHeight, rowTop);
    }

    /**
     * Measure view and its children with external inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block (but note that for external inputs, only the y coordinate of each
     * position is later used for positioning.)</li>
     * </ol>
     * </p>
     */
    private void measureExternalInputs(int widthMeasureSpec, int heightMeasureSpec) {
        int maxInputFieldsWidth = MIN_WIDTH;
        // Initialize max Statement width as zero so presence of Statement inputs can be determined
        // later; apply minimum size after that.
        mMaxStatementFieldsWidth = 0;

        int maxInputChildWidth = 0;
        int maxStatementChildWidth = 0;

        // First pass - measure fields and children of all inputs.
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);

            switch (inputView.getInput().getType()) {
                case Input.TYPE_VALUE: {
                    maxInputChildWidth =
                            Math.max(maxInputChildWidth, inputView.getTotalChildWidth());
                    // fall through
                }
                default:
                case Input.TYPE_DUMMY: {
                    maxInputFieldsWidth =
                            Math.max(maxInputFieldsWidth, inputView.getTotalFieldWidth());
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    mMaxStatementFieldsWidth =
                            Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());
                    maxStatementChildWidth =
                            Math.max(maxStatementChildWidth, inputView.getTotalChildWidth());
                    break;
                }
            }
        }

        // If there was a statement, force all other input fields to be at least as wide as required
        // by the Statement field plus port width.
        if (mMaxStatementFieldsWidth > 0) {
            mMaxStatementFieldsWidth = Math.max(mMaxStatementFieldsWidth, MIN_WIDTH);
            maxInputFieldsWidth = Math.max(maxInputFieldsWidth,
                    mMaxStatementFieldsWidth + mPatchManager.mStatementInputIndent);
        }

        // Second pass - force all inputs to render fields with the same width and compute positions
        // for all inputs.
        int rowTop = 0;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // Force all Statement inputs to have the same field width.
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);
            } else {
                // Force all Dummy and Value inputs to have the same field width.
                inputView.setFieldLayoutWidth(maxInputFieldsWidth);
            }
            inputView.measure(widthMeasureSpec, heightMeasureSpec);

            mInputLayoutOrigins.get(i).set(0, rowTop);

            // The block height is the sum of all the row heights.
            rowTop += inputView.getMeasuredHeight();
        }

        // Block content width is the width of the longest row.
        mBlockContentWidth = Math.max(maxInputFieldsWidth, mMaxStatementFieldsWidth);
        mBlockContentHeight = Math.max(mPatchManager.mMinBlockHeight, rowTop);

        // Add space for connector if there is at least one Value input.
        mBlockContentWidth += mPatchManager.mBlockTotalPaddingX;
        if (mHasValueInput) {
            mBlockContentWidth += mPatchManager.mValueInputWidth;
        }

        // Maximum total width of all value inputs is the sum of maximum field and child widths,
        // plus space for field padding and Value input connector, minus overlap of input and output
        // connectors.
        final int maxValueInputTotalWidth = maxInputFieldsWidth + maxInputChildWidth +
                mPatchManager.mBlockTotalPaddingX + mPatchManager.mValueInputWidth -
                mPatchManager.mOutputConnectorWidth;

        // Maximum total width of all Statement inputs is the sum of maximum field and and widths,
        // plus field padding on the left and C-connector padding in the middle.
        final int maxStatementInputTotalWidth = mMaxStatementFieldsWidth + maxStatementChildWidth +
                mPatchManager.mBlockLeftPadding +
                mPatchManager.mStatementInputPadding;

        // View width is maximum of content width and the Value input and Statement input total
        // widths.
        mBlockViewSize.x = Math.max(mBlockContentWidth,
                Math.max(maxValueInputTotalWidth, maxStatementInputTotalWidth));
    }

    /**
     * A block is responsible for initializing the views all of its fields and sub-blocks,
     * meaning both inputs and next blocks.
     *
     * @param parentGroup The group the current block and all next blocks live in.
     */
    private void initViews(Context context, int blockStyle, BlockGroup parentGroup) {
        List<Input> inputs = mBlock.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            InputView inputView = new InputView(context, blockStyle, in, mHelper);
            mInputViews.add(inputView);
            addView(inputView);
            if (in.getType() != Input.TYPE_DUMMY && in.getConnection().getTargetBlock() != null) {
                // Blocks connected to inputs live in their own BlockGroups.
                BlockGroup bg = new BlockGroup(context, mHelper);
                mHelper.obtainBlockView(context, in.getConnection().getTargetBlock(),
                        bg, mConnectionManager, mTouchHandler);
                inputView.setChildView(bg);
            }
            if (in.getType() == Input.TYPE_VALUE) {
                mHasValueInput = true;
            }
        }

        if (mBlock.getNextBlock() != null) {
            // Next blocks live in the same BlockGroup.
            mHelper.obtainBlockView(mBlock.getNextBlock(), parentGroup, mConnectionManager,
                    mTouchHandler);
        }

        mInputCount = mInputViews.size();
        resizeList(mInputConnectorOffsets);
        resizeList(mInputLayoutOrigins);
    }

    private void initDrawingObjects() {
        final int blockColor = mBlock.getColour();
        mBlockColorFilter = new ColorMatrixColorFilter(new float[]{
                0, 0, 0, Color.red(blockColor) / 255f, 0,
                0, 0, 0, Color.green(blockColor) / 255f, 0,
                0, 0, 0, Color.blue(blockColor) / 255f, 0,
                0, 0, 0, 1, 0
        });

        mFillPaint.setColor(mBlock.getColour());
        mFillPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Adjust size of an {@link ArrayList} of {@link ViewPoint} objects to match the size of
     * {@link #mInputViews}.
     */
    private void resizeList(ArrayList<ViewPoint> list) {
        if (list.size() != mInputCount) {
            list.ensureCapacity(mInputCount);
            if (list.size() < mInputCount) {
                for (int i = list.size(); i < mInputCount; i++) {
                    list.add(new ViewPoint());
                }
            } else {
                while (list.size() > mInputCount) {
                    list.remove(list.size() - 1);
                }
            }
        }
    }

    /**
     * Position patches for block rendering and connectors.
     */
    private void layoutPatchesAndConnectors() {
        mBlockPatches.clear();
        mFillRects.clear();

        // Leave room on the left for margin (accomodates optional output connector) and block
        // padding (accomodates block boundary).
        int xFrom = mLayoutMarginLeft + mPatchManager.mBlockLeftPadding;

        // For inline inputs, the upper horizontal coordinate of the block boundary varies by
        // section and changes after each Statement input. For external inputs, it is constant as
        // computed in measureExternalInputs.
        int xTo = mLayoutMarginLeft;
        int inlineRowIdx = 0;
        if (mBlock.getInputsInline()) {
            xTo += mInlineRowWidth.get(inlineRowIdx);
        } else {
            xTo += mBlockContentWidth;
        }

        // Position top-left corner drawable. Retain drawable object so we can position bottom-left
        // drawable correctly.
        int yTop = 0;
        final NinePatchDrawable tlDrawable = addTopLeftPatch(xFrom, yTop);

        // Position inputs and connectors.
        for (int i = 0; i < mInputCount; ++i) {
            final InputView inputView = mInputViews.get(i);
            final ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);

            // Start filling background of the input based on its origin and measured size.
            fillRectBySize(xFrom + inputLayoutOrigin.x, inputLayoutOrigin.y,
                    inputView.getFieldLayoutWidth(), inputView.getRowHeight());

            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY: {
                    if (!mBlock.getInputsInline()) {
                        addDummyBoundaryPatch(i, xTo, inputView, inputLayoutOrigin);
                    }
                    break;
                }
                case Input.TYPE_VALUE: {
                    if (mBlock.getInputsInline()) {
                        addInlineValueInputPatch(
                                i, inlineRowIdx, xFrom, inputView, inputLayoutOrigin);

                    } else {
                        addExternalValueInputPatch(i, xTo, inputView, inputLayoutOrigin);
                    }
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    // For external inputs, the horizontal end coordinate of the connector bottom is
                    // the same as the one on top. For inline inputs, however, it is the next entry
                    // in the width-by-row table.
                    int xToBottom = xTo;
                    if (mBlock.getInputsInline()) {
                        ++inlineRowIdx;
                        xToBottom = xFrom + mInlineRowWidth.get(inlineRowIdx) -
                                mPatchManager.mBlockLeftPadding;
                    }

                    // Place the connector patches.
                    addStatementInputPatches(
                            i, xFrom, xTo, xToBottom, inputView, inputLayoutOrigin);

                    // Set new horizontal end coordinate for subsequent inputs.
                    xTo = xToBottom;
                    break;
                }
            }
        }

        // Select and position correct patch for bottom and left-hand side of the block, including
        // bottom-left corner.
        int blResource = R.drawable.bl_default;
        if (mBlock.getNextConnection() != null) {
            setPointMaybeFlip(mNextConnectorOffset, xFrom, mNextBlockVerticalOffset);
            blResource = R.drawable.bl_next;
        }
        final NinePatchDrawable blDrawable = getColoredPatchDrawable(blResource);
        setBoundsMaybeFlip(blDrawable, mLayoutMarginLeft, tlDrawable.getIntrinsicHeight(),
                xTo, mBlockViewSize.y);
        mBlockPatches.add(blDrawable);

        // Finish the final rect, if there is one.
        finishFillRect();
    }

    /**
     * Add the top-left corner drawable.
     *
     * @param xFrom Horizontal position for the drawable.
     * @param yTop Vertical position for the drawable.
     *
     * @return The added drawable. This can be used to position other drawables, e.g., the
     * bottom-left drawable, relative to it.
     */
    @NonNull
    private NinePatchDrawable addTopLeftPatch(int xFrom, int yTop) {
        // Select and position the correct patch for the top and left block sides including the
        // top-left corner.
        NinePatchDrawable tlDrawable;
        if (mBlock.getPreviousConnection() != null) {
            setPointMaybeFlip(mPreviousConnectorOffset, xFrom, yTop);
            tlDrawable = getColoredPatchDrawable(R.drawable.tl_prev);
            setBoundsMaybeFlip(tlDrawable,
                    mLayoutMarginLeft, 0, mLayoutMarginLeft + mBlockContentWidth,
                    tlDrawable.getIntrinsicHeight());
        } else if (mBlock.getOutputConnection() != null) {
            setPointMaybeFlip(mOutputConnectorOffset, xFrom, yTop);
            tlDrawable = getColoredPatchDrawable(R.drawable.tl_output);
            setBoundsMaybeFlip(tlDrawable,
                    0, 0, mBlockContentWidth + mPatchManager.mOutputConnectorWidth,
                    tlDrawable.getIntrinsicHeight());
        } else {
            tlDrawable = getColoredPatchDrawable(R.drawable.tl_default);
            setBoundsMaybeFlip(tlDrawable,
                    mLayoutMarginLeft, 0, mLayoutMarginLeft + mBlockContentWidth,
                    tlDrawable.getIntrinsicHeight());
        }
        mBlockPatches.add(tlDrawable);
        return tlDrawable;
    }

    /**
     * Add boundary patch for external Dummy input.
     *
     * @param i The index of the current input. This is used to determine whether to position patch
     * vertically below the field top boundary to account for the block's top boundary.
     * @param xTo Horizontal coordinate to which the patch should extend. The starting coordinate
     * is determined from this by subtracting patch width.
     * @param inputView The {@link InputView} for the current input. This is used to determine patch
     * height.
     * @param inputLayoutOrigin The layout origin for the current input. This is used to determine
     * the vertical position for the patch.
     */
    private void addDummyBoundaryPatch(int i, int xTo, InputView inputView,
                                       ViewPoint inputLayoutOrigin) {
        // For external dummy inputs, put a patch for the block boundary.
        final NinePatchDrawable inputDrawable =
                getColoredPatchDrawable(R.drawable.dummy_input);
        int width = inputDrawable.getIntrinsicWidth();
        if (mHasValueInput) {
            // Stretch the patch horizontally if this block has at least one value
            // input, so that the dummy input block boundary is as thick as the
            // boundary with value input connector.
            width += mPatchManager.mValueInputWidth;
        }
        setBoundsMaybeFlip(inputDrawable,
                xTo - width, inputLayoutOrigin.y +
                        (i > 0 ? 0 : mPatchManager.mBlockTopPadding),
                xTo, inputLayoutOrigin.y + inputView.getMeasuredHeight());
        mBlockPatches.add(inputDrawable);
    }

    /**
     * Add connector patch for external Value inputs.
     *
     * @param i The index of the current input. This is used to determine whether to position patch
     * vertically below the field top boundary to account for the block's top boundary.
     * @param xTo Horizontal coordinate to which the patch should extend. The starting coordinate
     * is determined from this by subtracting patch width.
     * @param inputView The {@link InputView} for the current input. This is used to determine patch
     * height.
     * @param inputLayoutOrigin The layout origin for the current input. This is used to determine
     * the vertical position for the patch.
     */
    private void addExternalValueInputPatch(int i, int xTo, InputView inputView, ViewPoint inputLayoutOrigin) {
        // Position patch and connector for external value input.
        mInputConnectorOffsets.get(i).set(xTo, inputLayoutOrigin.y);

        final NinePatchDrawable inputDrawable =
                getColoredPatchDrawable(R.drawable.value_input_external);
        setBoundsMaybeFlip(inputDrawable,
                xTo - inputDrawable.getIntrinsicWidth(),
                inputLayoutOrigin.y + mPatchManager.mBlockTopPadding,
                xTo, inputLayoutOrigin.y + inputView.getMeasuredHeight());
        mBlockPatches.add(inputDrawable);

        if (i > 0) {
            // If this is not the first input in the block, then a gap above the
            // input connector patch must be closed by a non-input boundary patch.
            // The gap is the result of the need to not draw over the top block
            // boundary.
            final NinePatchDrawable boundaryGapDrawable =
                    getColoredPatchDrawable(R.drawable.dummy_input);
            setBoundsMaybeFlip(boundaryGapDrawable,
                    xTo - inputDrawable.getIntrinsicWidth(),
                    inputLayoutOrigin.y, xTo,
                    inputLayoutOrigin.y + mPatchManager.mBlockTopPadding);
            mBlockPatches.add(boundaryGapDrawable);
        }
    }

    /**
     * Add cutout patch for inline Value inputs.
     * <p/>
     * The inline input nine patch includes the entirety of the cutout shape, including the
     * connector, and stretches to fit all child blocks.
     * <p/>
     * An inline input is usually drawn with an input 9-patch and three rects for padding between
     * inputs: one above, one below, and one after (i.e., to  the right in LtR and to the left in
     * RtL).
     *
     * @param i The index of the input in the block.
     * @param inlineRowIdx The (horizontal) index of the input in the current input row.
     * @param blockFromX The horizontal start position input views in the block.
     * @param inputView The input view.
     * @param inputLayoutOrigin Layout origin for the current input view.
     */
    private void addInlineValueInputPatch(int i, int inlineRowIdx, int blockFromX,
                                          InputView inputView, ViewPoint inputLayoutOrigin) {
        // Determine position for inline connector cutout.
        final int cutoutX = blockFromX + inputLayoutOrigin.x + inputView.getInlineInputX();
        final int cutoutY = inputLayoutOrigin.y + mPatchManager.mBlockTopPadding;
        setPointMaybeFlip(mInputConnectorOffsets.get(i), cutoutX, cutoutY);

        // Fill above inline input connector, unless first row, where connector top
        // is aligned with block boundary patch.
        if (inlineRowIdx > 0) {
            fillRectBySize(cutoutX, inputLayoutOrigin.y,
                    inputView.getTotalChildWidth(), mPatchManager.mBlockTopPadding);
            finishFillRect();  // Prevent filling through the inline connector.
        }

        // Position a properly-sized input cutout patch.
        final NinePatchDrawable inputDrawable =
                getColoredPatchDrawable(R.drawable.value_input_inline);
        setBoundsMaybeFlip(inputDrawable, cutoutX, cutoutY,
                cutoutX + inputView.getTotalChildWidth(),
                cutoutY + inputView.getTotalChildHeight());
        mBlockPatches.add(inputDrawable);

        // Fill below inline input cutout.
        final int cutoutEndX = cutoutX + inputView.getTotalChildWidth();
        final int cutoutEndY = inputLayoutOrigin.y + inputView.getRowHeight();
        fillRect(cutoutX, cutoutY + inputView.getTotalChildHeight(),
                cutoutEndX, cutoutEndY);

        // Fill after inline input cutout.
        fillRect(cutoutEndX, inputLayoutOrigin.y,
                inputLayoutOrigin.x + inputView.getMeasuredWidth(), cutoutEndY);

        // If this is either the last input in the block, or the next input is a Statement, then
        // this is the final input in the current row. In this case, put a boundary patch.
        final int nextI = i + 1;
        if ((nextI == mInputCount) ||
                (mInputViews.get(nextI).getInput().getType() == Input.TYPE_STATEMENT)) {
            final NinePatchDrawable blockBorderDrawable =
                    getColoredPatchDrawable(R.drawable.dummy_input);

            // Horizontal patch position is the position of inputs in the block, plus offset of the
            // current input in its row, plus padding before and after the input fields.
            final int patchX = blockFromX + mInlineRowWidth.get(inlineRowIdx) -
                    mPatchManager.mBlockTotalPaddingX;
            // Vertical patch position is the input layout origin, plus room for block boundary if
            // this is the first input row.
            final int patchY = inputLayoutOrigin.y +
                    (inlineRowIdx > 0 ? 0 : mPatchManager.mBlockTopPadding);

            setBoundsMaybeFlip(blockBorderDrawable,
                    patchX, patchY, patchX + mPatchManager.mBlockRightPadding, cutoutEndY);
            mBlockPatches.add(blockBorderDrawable);

            // Also at the end of the current input row, fill background up to
            // block boundary.
            fillRect(inputLayoutOrigin.x + mPatchManager.mBlockLeftPadding +
                    inputView.getMeasuredWidth(), patchY, patchX, cutoutEndY);
        }
    }

    /**
     * Add patches (top and bottom) for a Statement input connector.
     *
     * @param i Index of the input.
     * @param xFrom Horizontal offset of block content.
     * @param xToAbove Horizontal end coordinate for connector above input.
     * @param xToBelow Horizontal end coordinate for connector below input.
     * @param inputView The view for this input.
     * @param inputLayoutOrigin Layout origin for this input.
     */
    private void addStatementInputPatches(int i, int xFrom, int xToAbove, int xToBelow,
                                          InputView inputView, ViewPoint inputLayoutOrigin) {
        // Position connector.
        int xOffset = xFrom + inputView.getFieldLayoutWidth();
        setPointMaybeFlip(mInputConnectorOffsets.get(i), xOffset, inputLayoutOrigin.y);

        // Position patch for the top part of the Statement connector. This patch is
        // stretched only horizontally to extend to the block boundary.
        final NinePatchDrawable statementTopDrawable =
                getColoredPatchDrawable(R.drawable.statementinput_top);
        setBoundsMaybeFlip(statementTopDrawable, xOffset, inputLayoutOrigin.y,
                xToAbove, inputLayoutOrigin.y + statementTopDrawable.getIntrinsicHeight());
        mBlockPatches.add(statementTopDrawable);

        // Position patch for the bottom part of the Statement connector. The bottom
        // patch is stretched horizontally, like the top patch, but also vertically to
        // accomodate height of the input fields as well as the size of any connected
        // blocks.
        final NinePatchDrawable statementBottomDrawable =
                getColoredPatchDrawable(R.drawable.statementinput_bottom);

        final int connectorHeight =
                Math.max(inputView.getTotalChildHeight(),
                        inputView.getMeasuredHeight());

        setBoundsMaybeFlip(statementBottomDrawable, xOffset,
                inputLayoutOrigin.y + statementTopDrawable.getIntrinsicHeight(),
                xToBelow, inputLayoutOrigin.y + connectorHeight);
        mBlockPatches.add(statementBottomDrawable);
    }

    /**
     * Draw dots at the model's location of all connections on this block, for debugging.
     *
     * @param c The canvas to draw on.
     */
    private void drawConnectorCenters(Canvas c) {
        List<Connection> connections = mBlock.getAllConnections();
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < connections.size(); i++) {
            Connection conn = connections.get(i);
            if (conn.inDragMode()) {
                if (conn.isConnected()) {
                    paint.setColor(Color.RED);
                } else {
                    paint.setColor(Color.MAGENTA);
                }
            } else {
                if (conn.isConnected()) {
                    paint.setColor(Color.GREEN);
                } else {
                    paint.setColor(Color.CYAN);
                }
            }

            // Compute connector position relative to this view from its offset to block origin in
            // Workspace coordinates.
            mTempWorkspacePoint.set(
                    conn.getPosition().x - mBlock.getPosition().x,
                    conn.getPosition().y - mBlock.getPosition().y);
            mHelper.workspaceToVirtualViewDelta(mTempWorkspacePoint, mTempConnectionPosition);
            if (mHelper.useRtL()) {
                mTempConnectionPosition.x = mBlockViewSize.x - mTempConnectionPosition.x;
            }
            c.drawCircle(mTempConnectionPosition.x, mTempConnectionPosition.y, 10, paint);
        }
    }

    /**
     * @return The number of {@link InputView} instances inside this view.
     */
    @VisibleForTesting
    int getInputViewCount() {
        return mInputViews.size();
    }

    /**
     * @return The {@link InputView} for the {@link Input} at the given index.
     */
    @VisibleForTesting
    InputView getInputView(int index) {
        return mInputViews.get(index);
    }

    /**
     * Correctly set the locations of the connections based on their offsets within the
     * {@link BlockView} and the position of the {@link BlockView} itself.  Can be used when the
     * block has moved but not changed shape (e.g. during a drag).
     */
    @VisibleForTesting
    public void updateConnectorLocations() {
        // Ensure we have the right block location before we update the connections.
        updateBlockPosition();

        if (mConnectionManager == null) {
            return;
        }
        final WorkspacePoint blockWorkspacePosition = mBlock.getPosition();
        if (mBlock.getPreviousConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mPreviousConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getPreviousConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        if (mBlock.getNextConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mNextConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getNextConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        if (mBlock.getOutputConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mOutputConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getOutputConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            Connection conn = inputView.getInput().getConnection();
            if (conn != null) {
                mHelper.virtualViewToWorkspaceDelta(
                        mInputConnectorOffsets.get(i), mTempWorkspacePoint);
                mConnectionManager.moveConnectionTo(conn,
                        blockWorkspacePosition, mTempWorkspacePoint);
                if (conn.isConnected()) {
                    ((BlockGroup) inputView.getChildView()).updateAllConnectorLocations();
                }
            }
        }
    }

    /**
     * Add a rectangular area for filling either by creating a new rectangle or extending an
     * existing one.
     * <p/>
     * If the rectangle defined by the method arguments is aligned with the in-progress rectangle
     * {@link #mNextFillRect} either horizontally or vertically, then the two are joined. Otherwise,
     * {@link #mNextFillRect} is finished and committed to the list of rectangles to draw and a new
     * rectangle is begun with the given method arguments.
     * <p/>
     * Note that rectangles are joined even if there is a gap between them. This fills padding areas
     * between inline inputs in the same row without any additional code. However, this assumes that
     * whenever there is an intended gap between aligned rectangles, then there is at last one
     * rectangle of different size (or with unaligned position) between them. If this assumption is
     * violated, call {@link #finishFillRect()} prior to the next call to this method.
     *
     * @param left Left coordinate of the new rectangle in LtR mode. In RtL mode, coordinates are
     * automatically flipped when the rectangle is committed by calling {@link #finishFillRect()}.
     * @param top Top coordinate of the new rectangle.
     * @param right Right coordinate of the new rectangle in LtR mode. In RtL mode, coordinates are
     * automatically flipped when the rectangle is committed by calling {@link #finishFillRect()}.
     * @param bottom Bottom coordinate of the new rectangle.
     */
    private void fillRect(int left, int top, int right, int bottom) {
        if (mNextFillRect != null) {
            if ((mNextFillRect.left == left) && (mNextFillRect.right == right)) {
                assert mNextFillRect.top <= top;  // New rectangle must not start above current.
                mNextFillRect.bottom = bottom;
                return;
            } else if ((mNextFillRect.top == top) && (mNextFillRect.bottom == bottom)) {
                assert mNextFillRect.left <= left;  // New rectangle must not start left of current.
                mNextFillRect.right = right;
                return;
            } else {
                finishFillRect();
            }
        }

        mNextFillRect = new Rect(left, top, right, bottom);
    }

    /**
     * Convenience wrapper for {@link #fillRect(int, int, int, int)} taking rectangle size
     * rather than upper bounds.
     * <p/>
     * This wrapper converts width and height of the given rectangle to right and bottom
     * coordinates, respectively. That makes client code more readable in places where the rectangle
     * is naturally defined by its origin and size.
     *
     * @param left Left coordinate of the new rectangle in LtR mode. In RtL mode, coordinates are
     * automatically flipped when the rectangle is committed by calling {@link #finishFillRect()}.
     * @param top Top coordinate of the new rectangle.
     * @param width Width of the new rectangle.
     * @param height Height of the new rectangle.
     */
    private void fillRectBySize(int left, int top, int width, int height) {
        fillRect(left, top, left + width, top + height);
    }

    /**
     * Finish the current fill rectangle.
     * <p/>
     * The current rectangle is cropped against the vertical block boundaries with padding
     * considered, and afterwards committed to the {@link #mFillRects} list.
     * <p/>
     * Note that horizontal block padding is assumed to have been considered prior to calling
     * {@link #fillRect(int, int, int, int)}. This is because horizontal block size can
     * vary across rows in a block with inline inputs.
     * <p/>
     * In Right-to-Left mode, the horizontal rectangle boundaries are mirrored w.r.t. the right-hand
     * side of the view.
     */
    private void finishFillRect() {
        if (mNextFillRect != null) {
            mNextFillRect.top = Math.max(mNextFillRect.top, mPatchManager.mBlockTopPadding);
            mNextFillRect.bottom = Math.min(mNextFillRect.bottom,
                    mBlockContentHeight - mPatchManager.mBlockBottomPadding);

            // In RtL mode, mirror Rect w.r.t. right-hand side of the block area.
            if (mHelper.useRtL()) {
                final int left = mNextFillRect.left;
                mNextFillRect.left = mBlockViewSize.x - mNextFillRect.right;
                mNextFillRect.right = mBlockViewSize.x - left;
            }

            mFillRects.add(mNextFillRect);
            mNextFillRect = null;
        }
    }

    /**
     * Set bounds of a {@link Drawable}, and flip x bounds in RtL mode.
     *
     * @param drawable The drawable whose bounds to set.
     * @param left The new left coordinate in LtR mode.
     * @param top The new top coordinate.
     * @param right The new right coordinate in LtR mode.
     * @param bottom The new bottom coordinate.
     */
    private void setBoundsMaybeFlip(Drawable drawable, int left, int top, int right, int bottom) {
        if (mHelper.useRtL()) {
            drawable.setBounds(mBlockViewSize.x - right, top, mBlockViewSize.x - left, bottom);
        } else {
            drawable.setBounds(left, top, right, bottom);
        }
    }

    /**
     * Set a {@link ViewPoint} and flip x coordinate in RtL mode.
     *
     * @param viewPoint The point in view coordinates to set.
     * @param x The new x coordinate in LtR mode.
     * @param y The  new y coordinate.
     */
    private void setPointMaybeFlip(ViewPoint viewPoint, int x, int y) {
        viewPoint.set(x, y);
    }

    private NinePatchDrawable getColoredPatchDrawable(int id) {
        NinePatchDrawable drawable = mPatchManager.getPatchDrawable(id, mHelper.useRtL());
        drawable.setColorFilter(mBlockColorFilter);
        return drawable;
    }

    /**
     * @return Vertical offset for positioning the "Next" block (if one exists). This is relative to
     * the top of this view's area.
     */
    int getNextBlockVerticalOffset() {
        return mNextBlockVerticalOffset;
    }

    /**
     * @return Layout margin on the left-hand side of the block (for optional Output connector).
     */
    int getLayoutMarginLeft() {
        return mLayoutMarginLeft;
    }
}
