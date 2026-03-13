export const theme = {
  // Colors
  primary: "#d97706",
  primaryText: "#fff",
  sidebarBg: "#fef7ed",
  contentBg: "#fffcf5",
  textPrimary: "#44403c",
  textSecondary: "#a8a29e",
  border: "#e7e5e4",
  loginBg: "#fef7ed",
  error: "#e53e3e",
  placeholder: "#c4b5a4",

  // Hover/Active (warm-tinted)
  hoverBg: "rgba(68,64,60,0.05)",
  activeBg: "rgba(68,64,60,0.08)",
  overlayBg: "rgba(0,0,0,0.20)",

  // Layout
  sidebarWidth: 320,
  editorMaxWidth: 850,
  editorPadX: 56,
  treeIndent: 20,

  // Radius
  radius: 8,
  btnRadius: 20,
  cardRadius: 16,

  // Shadow
  shadow: "0 2px 10px rgba(0,0,0,0.08)",
  dialogShadow: "0 4px 20px rgba(0,0,0,0.12)",

  // Typography
  fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif",
  bodyFontSize: 15,
  titleFontSize: 34,
  sidebarFontSize: 14,
  smallFontSize: 13,
  lineHeight: 1.8,

  // Tree
  treeItemHeight: 32,
  treeIconExpanded: "⌄",
  treeIconCollapsed: "›",

  // Buttons (ghost style)
  btnPadding: "6px 18px",
  btnWeight: 500 as const,

  // Dialog
  dialogWidth: 460,
  dialogPad: 28,

  // Collab
  avatarSize: 26,
} as const;
