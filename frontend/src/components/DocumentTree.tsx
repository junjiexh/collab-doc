import { useCallback, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  DndContext,
  closestCenter,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { TreeItem } from "./TreeItem";
import type { TreeNode, FlattenedItem } from "../utils/tree";
import { flattenTree, isAncestor } from "../utils/tree";

interface DocumentTreeProps {
  tree: TreeNode[];
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onMove: (id: string, newParentId: string | null, newIndex: number) => void;
}

function SortableTreeItem({
  item,
  isActive,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onClick,
}: {
  item: FlattenedItem;
  isActive: boolean;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onClick: (id: string) => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.id });

  const style = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  return (
    <TreeItem
      ref={setNodeRef}
      id={item.id}
      title={item.title}
      depth={item.depth}
      hasChildren={item.children.length > 0}
      isExpanded={expandedIds.has(item.id)}
      isActive={isActive}
      isDragging={isDragging}
      onToggle={() => onToggle(item.id)}
      onCreateChild={() => onCreateChild(item.id)}
      onDelete={() => onDelete(item.id)}
      onClick={() => onClick(item.id)}
      handleProps={{ ...attributes, ...listeners }}
      style={style}
    />
  );
}

export default function DocumentTree({
  tree,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onMove,
}: DocumentTreeProps) {
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();
  const [activeId, setActiveId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  const flatItems = useMemo(
    () => flattenTree(tree, expandedIds),
    [tree, expandedIds]
  );

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      setActiveId(null);
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const activeItem = flatItems.find((i) => i.id === active.id);
      const overItem = flatItems.find((i) => i.id === over.id);
      if (!activeItem || !overItem) return;

      // Prevent dropping a parent into its own descendant
      if (isAncestor(tree, String(active.id), String(over.id))) return;

      // Move to same parent as the target, at target's position
      const newParentId = overItem.parentId;
      const siblings = flatItems.filter(
        (i) => i.parentId === newParentId && i.id !== String(active.id)
      );
      const overIndex = siblings.findIndex((i) => i.id === over.id);
      const newIndex = overIndex === -1 ? 0 : overIndex;

      onMove(String(active.id), newParentId, newIndex);
    },
    [flatItems, tree, onMove]
  );

  const activeItem = activeId
    ? flatItems.find((i) => i.id === activeId)
    : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext
        items={flatItems.map((i) => i.id)}
        strategy={verticalListSortingStrategy}
      >
        {flatItems.map((item) => (
          <SortableTreeItem
            key={item.id}
            item={item}
            isActive={docId === item.id}
            expandedIds={expandedIds}
            onToggle={onToggle}
            onCreateChild={onCreateChild}
            onDelete={onDelete}
            onClick={(id) => navigate(`/doc/${id}`)}
          />
        ))}
      </SortableContext>

      <DragOverlay>
        {activeItem ? (
          <TreeItem
            id={activeItem.id}
            title={activeItem.title}
            depth={0}
            hasChildren={activeItem.children.length > 0}
            isExpanded={false}
            isActive={false}
            isDragging
            onToggle={() => {}}
            onCreateChild={() => {}}
            onDelete={() => {}}
          />
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
