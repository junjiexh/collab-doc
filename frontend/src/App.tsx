import { BrowserRouter, Routes, Route } from "react-router-dom";
import MainLayout from "./layouts/MainLayout";
import EditorPage from "./pages/EditorPage";
import WelcomePage from "./pages/WelcomePage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<WelcomePage />} />
          <Route path="/doc/:docId" element={<EditorPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
