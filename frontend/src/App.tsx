import { BrowserRouter, Routes, Route } from "react-router-dom";
import DocumentList from "./pages/DocumentList";
import EditorPage from "./pages/EditorPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<DocumentList />} />
        <Route path="/doc/:docId" element={<EditorPage />} />
      </Routes>
    </BrowserRouter>
  );
}
