import { forwardRef, HTMLAttributes } from "react";
import { theme } from "../theme";

interface TreeItemProps extends HTMLAttributes<HTMLDivElement> {
  id: string;
  title: string;
  depth: number;
  hasChildren: boolean;
  isExpanded: boolean;
  isActive: boolean;
  onToggle: () => void;
  onCreateChild: () => void;
  onDelete: () => void;
  isDragging?: boolean;
  handleProps?: Record<string, unknown>;
}

export const TreeItem = forwardRef<HTMLDivElement, TreeItemProps>(
  ({ id, title, depth, hasChildren, isExpanded, isActive, onToggle, onCreateChild, onDelete, isDragging, handleProps, style, ...props }, ref) => {
    return (
      <div ref={ref} style={{
        display: "flex", alignItems: "center",
        padding: "4px 8px", paddingLeft: `${depth * theme.treeIndent + 8}px`,
        minHeight: theme.treeItemHeight,
        transition: "padding-left 0.15s ease",
        cursor: "pointer", borderRadius: theme.radius,
        backgroundColor: isActive ? theme.activeBg : isDragging ? theme.hoverBg : "transparent",
        opacity: isDragging ? 0.5 : 1,
        userSelect: "none", fontSize: theme.sidebarFontSize,
        ...style,
      }} {...props}>
        <span onClick={(e) => { e.stopPropagation(); if (hasChildren) onToggle(); }}
          style={{ width: 20, display: "inline-flex", justifyContent: "center", color: theme.textSecondary, flexShrink: 0 }}>
          {hasChildren ? (isExpanded ? theme.treeIconExpanded : theme.treeIconCollapsed) : ""}
        </span>
        <span {...handleProps} style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {title || "Untitled"}
        </span>
        <span className="tree-item-actions" style={{ display: "flex", gap: 4, flexShrink: 0 }}>
          <button onClick={(e) => { e.stopPropagation(); onCreateChild(); }}
            style={{ border: "none", background: "none", cursor: "pointer", padding: "0 4px", fontSize: theme.sidebarFontSize, color: theme.textSecondary }} title="New sub-page">+</button>
          <button onClick={(e) => { e.stopPropagation(); onDelete(); }}
            style={{ border: "none", background: "none", cursor: "pointer", padding: "0 4px", fontSize: theme.sidebarFontSize, color: theme.textSecondary }} title="Delete">×</button>
        </span>
      </div>
    );
  }
);

TreeItem.displayName = "TreeItem";
