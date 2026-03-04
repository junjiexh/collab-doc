import { forwardRef, HTMLAttributes } from "react";

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
  (
    {
      id,
      title,
      depth,
      hasChildren,
      isExpanded,
      isActive,
      onToggle,
      onCreateChild,
      onDelete,
      isDragging,
      handleProps,
      style,
      ...props
    },
    ref
  ) => {
    return (
      <div
        ref={ref}
        style={{
          display: "flex",
          alignItems: "center",
          padding: "4px 8px",
          paddingLeft: `${depth * 20 + 8}px`,
          cursor: "pointer",
          borderRadius: 4,
          backgroundColor: isActive
            ? "rgba(0,0,0,0.08)"
            : isDragging
            ? "rgba(0,0,0,0.04)"
            : "transparent",
          opacity: isDragging ? 0.5 : 1,
          userSelect: "none",
          fontSize: 14,
          ...style,
        }}
        {...props}
      >
        <span
          onClick={(e) => {
            e.stopPropagation();
            if (hasChildren) onToggle();
          }}
          style={{
            width: 20,
            display: "inline-flex",
            justifyContent: "center",
            color: "#999",
            flexShrink: 0,
          }}
        >
          {hasChildren ? (isExpanded ? "▾" : "▸") : ""}
        </span>

        <span
          {...handleProps}
          style={{
            flex: 1,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {title || "Untitled"}
        </span>

        <span
          className="tree-item-actions"
          style={{
            display: "flex",
            gap: 4,
            flexShrink: 0,
          }}
        >
          <button
            onClick={(e) => {
              e.stopPropagation();
              onCreateChild();
            }}
            style={{
              border: "none",
              background: "none",
              cursor: "pointer",
              padding: "0 4px",
              fontSize: 14,
              color: "#666",
            }}
            title="New sub-page"
          >
            +
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDelete();
            }}
            style={{
              border: "none",
              background: "none",
              cursor: "pointer",
              padding: "0 4px",
              fontSize: 14,
              color: "#666",
            }}
            title="Delete"
          >
            ×
          </button>
        </span>
      </div>
    );
  }
);

TreeItem.displayName = "TreeItem";
