class MouseHandler extends MouseInputAdapter

	{

		MouseActions mouseActions = new MouseActions("gutter");
		boolean drag;
		int toolTipInitialDelay, toolTipReshowDelay;
		//{{{ mouseEntered() method
		public void mouseEntered(MouseEvent e)
		{

			ToolTipManager ttm = ToolTipManager.sharedInstance();

			toolTipInitialDelay = ttm.getInitialDelay();

			toolTipReshowDelay = ttm.getReshowDelay();

			ttm.setInitialDelay(0);

			ttm.setReshowDelay(0);

		} //}}}
		//{{{ mouseExited() method
		public void mouseExited(MouseEvent evt)

		{

			ToolTipManager ttm = ToolTipManager.sharedInstance();

			ttm.setInitialDelay(toolTipInitialDelay);

			ttm.setReshowDelay(toolTipReshowDelay);

		} //}}}


		//{{{ mousePressed() method
		public void mousePressed(MouseEvent e)
		{
			textArea.requestFocus();
			if(GUIUtilities.isPopupTrigger(e)
				|| e.getX() >= getWidth() - borderWidth * 2)
			{
				e.translatePoint(-getWidth(),0);
				textArea.mouseHandler.mousePressed(e);
				drag = true;
			}
			else
			{
				Buffer buffer = textArea.getBuffer();
				int screenLine = e.getY() / textArea.getPainter()
					.getFontMetrics().getHeight();
				int line = textArea.chunkCache.getLineInfo(screenLine)
					.physicalLine;
				if(line == -1)
					return;
				//{{{ Determine action
				String defaultAction;
				String variant;
				if(buffer.isFoldStart(line))
				{
					defaultAction = "toggle-fold";
					variant = "fold";
				}
				else if(structureHighlight
					&& textArea.isStructureHighlightVisible()
					&& textArea.lineInStructureScope(line))
				{
					defaultAction = "match-struct";
					variant = "struct";
				}
				else
					return;
				String action = mouseActions.getActionForEvent(
					e,variant);
				if(action == null)
					action = defaultAction;
				//}}}
				//{{{ Handle actions
				StructureMatcher.Match match = textArea
					.getStructureMatch();
				if(action.equals("select-fold"))
				{
					textArea.displayManager.expandFold(line,true);
					textArea.selectFold(line);
				}
				else if(action.equals("narrow-fold"))
				{
					int[] lines = buffer.getFoldAtLine(line);
					textArea.displayManager.narrow(lines[0],lines[1]);
				}
				else if(action.startsWith("toggle-fold"))
				{
					if(textArea.displayManager
						.isLineVisible(line + 1))
					{
						textArea.displayManager.collapseFold(line);
					}
					else
					{
						if(action.endsWith("-fully"))
						{
							textArea.displayManager
								.expandFold(line,
								true);
						}
						else
						{
							textArea.displayManager
								.expandFold(line,
								false);
						}
					}
				}
				else if(action.equals("match-struct"))
				{
					if(match != null)
						textArea.setCaretPosition(match.end);
				}
				else if(action.equals("select-struct"))
				{
					if(match != null)
					{
						match.matcher.selectMatch(
							textArea);
					}
				}
				else if(action.equals("narrow-struct"))
				{
					if(match != null)
					{
						int start = Math.min(
							match.startLine,
							textArea.getCaretLine());
						int end = Math.max(
							match.endLine,
							textArea.getCaretLine());
						textArea.displayManager.narrow(start,end);
					}
				} //}}}
			}
		} //}}}


		//{{{ mouseDragged() method

		public void mouseDragged(MouseEvent e)

		{

			if(drag /* && e.getX() >= getWidth() - borderWidth * 2 */)

			{

				e.translatePoint(-getWidth(),0);

				textArea.mouseHandler.mouseDragged(e);

			}

		} //}}}


		//{{{ mouseReleased() method

		public void mouseReleased(MouseEvent e)

		{

			if(drag && e.getX() >= getWidth() - borderWidth * 2)

			{

				e.translatePoint(-getWidth(),0);

				textArea.mouseHandler.mouseReleased(e);

			}


			drag = false;

		} //}}}

	} //}}}
