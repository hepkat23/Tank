--[[
Tank for KOReader: "Ask Tank" in the selection menu.
Answers come from:
  * the Tank app (Android only): it handles ChatGPT/Claude website login, so no API key;
  * any OpenAI-compatible API (OpenRouter, Gemini, Anthropic, ...) or a local server (LM Studio, Ollama).
Each book keeps its own chat in its sidecar (doc settings), so it travels with the book.
--]]
local Device = require("device")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local MultiInputDialog = require("ui/widget/multiinputdialog")
local TextViewer = require("ui/widget/textviewer")
local Trapper = require("ui/trapper")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local JSON = require("json")
local util = require("util")
local _ = require("gettext")
local T = require("ffi/util").template

local TANK_PACKAGE = "workshop.readingcompanion"
local MAX_SENT = 20 -- book intro + the most recent messages

local PRESETS = {
    { id = "openrouter",  name = "OpenRouter",    base = "https://openrouter.ai/api/v1", model = "deepseek/deepseek-v3.2", key = true },
    { id = "huggingface", name = "Hugging Face",  base = "https://router.huggingface.co/v1", model = "openai/gpt-oss-120b", key = true },
    { id = "gemini",      name = "Google Gemini", base = "https://generativelanguage.googleapis.com/v1beta/openai", model = "gemini-2.5-flash", key = true },
    { id = "anthropic",   name = "Anthropic",     base = "https://api.anthropic.com/v1", model = "claude-sonnet-5-5", key = true },
    { id = "openai",      name = "OpenAI",        base = "https://api.openai.com/v1", model = "gpt-5-mini", key = true },
    { id = "deepseek",    name = "DeepSeek",      base = "https://api.deepseek.com/v1", model = "deepseek-chat", key = true },
    { id = "mistral",     name = "Mistral",       base = "https://api.mistral.ai/v1", model = "mistral-small-latest", key = true },
    { id = "lmstudio",    name = "LM Studio (your computer)", base = "http://192.168.1.10:1234/v1", model = "", key = false },
    { id = "ollama",      name = "Ollama (your computer)",    base = "http://192.168.1.10:11434/v1", model = "", key = false },
    { id = "custom",      name = "Other (OpenAI-compatible)", base = "https://", model = "", key = false },
}

local function config() return G_reader_settings:readSetting("tank") or {} end
local function saveConfig(c) G_reader_settings:saveSetting("tank", c) end

local function preset(id)
    for _, p in ipairs(PRESETS) do if p.id == id then return p end end
    return PRESETS[1]
end

-- "app" (Android Tank app) or a preset id.
local function backend()
    return config().backend or (Device:isAndroid() and "app" or "openrouter")
end

local function apiSettings(id)
    local p = preset(id)
    local s = (config().api or {})[id] or {}
    local function pick(v, default) return (v and v ~= "") and v or default end
    return { preset = p, base = pick(s.base, p.base), key = s.key or "", model = pick(s.model, p.model) }
end

local function backendName(id)
    if id == "app" then return _("Tank app") end
    return preset(id).name
end

-- Runs in a subprocess: must only return plain tables.
local function request(s, messages)
    local http = require("socket.http")
    local ltn12 = require("ltn12")
    local socket = require("socket")
    local socketutil = require("socketutil")

    local sent = {}
    if #messages > 0 then table.insert(sent, messages[1]) end
    for i = math.max(2, #messages - (MAX_SENT - 2)), #messages do table.insert(sent, messages[i]) end

    local body = JSON.encode({ model = s.model, messages = sent })
    local headers = {
        ["Content-Type"] = "application/json",
        ["Content-Length"] = tostring(#body),
        ["X-Title"] = "Tank",
    }
    if s.key ~= "" then headers["Authorization"] = "Bearer " .. s.key end
    local sink = {}
    socketutil:set_timeout(20, 180)
    local code, resp_headers, status = socket.skip(1, http.request{
        url = s.base:gsub("/+$", "") .. "/chat/completions",
        method = "POST",
        headers = headers,
        source = ltn12.source.string(body),
        sink = ltn12.sink.table(sink),
    })
    socketutil:reset_timeout()
    if resp_headers == nil then
        return { error = tostring(status or code or "network unreachable") }
    end
    local content = table.concat(sink)
    local ok, data = pcall(JSON.decode, content, JSON.decode.simple)
    if code ~= 200 then
        local msg = ok and type(data) == "table" and type(data.error) == "table" and data.error.message
        return { error = "HTTP " .. tostring(code) .. ": " .. tostring(msg or content:sub(1, 300)) }
    end
    local choice = ok and type(data) == "table" and type(data.choices) == "table" and data.choices[1]
    local text = choice and type(choice.message) == "table" and choice.message.content
    if type(text) ~= "string" then return { error = "Unexpected reply: " .. content:sub(1, 200) } end
    return { text = text }
end

local Tank = WidgetContainer:extend{
    name = "tank",
    is_doc_only = true,
}

function Tank:init()
    self.ui.menu:registerToMainMenu(self)
    if not self.ui.highlight then return end
    self.ui.highlight:addToHighlightDialog("07_tank", function(this)
        return {
            text = _("Ask Tank"),
            callback = function()
                local text = util.cleanupSelectedText(this.selected_text.text)
                local before, after = this:getSelectedWordContext(80)
                this:onClose()
                self:ask(text, before, after)
            end,
        }
    end)
end

function Tank:bookTitle()
    local props = self.ui.doc_props or {}
    return props.display_title or props.title or "this book", props.authors
end

function Tank:intro()
    local title, authors = self:bookTitle()
    local by = authors and authors ~= "" and (" by " .. authors:gsub("\n", ", ")) or ""
    return title .. by .. "\n\n"
        .. "I'm reading this book and will send you words and passages as I go. "
        .. "For each one, explain only the selected words: what they mean here, in the context of the book, in a few sentences, in English. "
        .. "The passage around them is background only: use it to understand the selection, but don't summarise or interpret the rest of it. "
        .. "If I add a question, answer that question. "
        .. "Don't spoil anything past the page I'm on."
end

function Tank:lookup(text, before, after)
    local where = {}
    local page, total = self.ui:getCurrentPage(), self.ui.document:getPageCount()
    if page and total then table.insert(where, T("Page %1/%2", page, total)) end
    local chapter = self.ui.toc and self.ui.toc:getTocTitleOfCurrentPage()
    if chapter and chapter ~= "" then table.insert(where, '"' .. chapter .. '"') end
    local out = (#where > 0 and (table.concat(where, " · ") .. "\n") or "") .. 'Selected: "' .. text .. '"'
    if (before and before ~= "") or (after and after ~= "") then
        out = out .. "\n\nContext (background only, explain just the selection):\n" .. (before or "") .. " " .. text .. " " .. (after or "")
    end
    return out
end

function Tank:messages() return self.ui.doc_settings:readSetting("tank_chat") or {} end
function Tank:saveMessages(m) self.ui.doc_settings:saveSetting("tank_chat", m) end

function Tank:ask(text, before, after)
    if backend() == "app" then
        -- Save progress so the app sees the current book and page, then hand over.
        self.ui:saveSettings()
        require("android").dictLookup(text, TANK_PACKAGE, "send")
        return
    end
    local messages = self:messages()
    local q = self:lookup(text, before, after)
    if #messages == 0 then q = self:intro() .. "\n\n" .. q end
    table.insert(messages, { role = "user", content = q })
    self:send(messages)
end

function Tank:send(messages)
    local s = apiSettings(backend())
    if s.preset.key and s.key == "" then
        UIManager:show(InfoMessage:new{ text = T(_("Add your %1 key first: menu → Tank → AI settings."), s.preset.name) })
        return
    end
    if s.model == "" then
        UIManager:show(InfoMessage:new{ text = _("Set a model first: menu → Tank → AI settings.") })
        return
    end
    Trapper:wrap(function()
        local completed, result = Trapper:dismissableRunInSubprocess(function()
            return request(s, messages)
        end, T(_("Asking %1…"), s.preset.name))
        if not completed then return end
        if type(result) ~= "table" or not result.text then
            table.remove(messages) -- drop the unanswered question
            UIManager:show(InfoMessage:new{ text = T(_("Tank couldn't get an answer:\n%1"), result and result.error or "?") })
            return
        end
        table.insert(messages, { role = "assistant", content = result.text })
        self:saveMessages(messages)
        self:showChat(messages)
    end)
end

-- The chat as plain text: the question's "Selected:" line, then the answer.
local function render(messages)
    local parts = {}
    for _, m in ipairs(messages) do
        if m.role == "user" then
            local sel = m.content:match('Selected: "(.-)"\n') or m.content:match('Selected: "(.-)"$') or m.content
            table.insert(parts, "▶ " .. sel)
        else
            table.insert(parts, (m.content:gsub("%*%*(.-)%*%*", "%1")))
        end
    end
    return table.concat(parts, "\n\n")
end

function Tank:showChat(messages)
    messages = messages or self:messages()
    if #messages == 0 then
        UIManager:show(InfoMessage:new{ text = _("No Tank chat for this book yet. Select text and tap Ask Tank.") })
        return
    end
    local viewer
    viewer = TextViewer:new{
        title = T(_("Tank · %1"), (self:bookTitle())),
        text = render(messages),
        justified = false,
        buttons_table = {{
            { text = _("Follow-up"), callback = function()
                UIManager:close(viewer)
                self:followUp(messages)
            end },
            { text = _("Close"), callback = function() UIManager:close(viewer) end },
        }},
    }
    UIManager:show(viewer)
    -- Start at the newest answer.
    if viewer.scroll_text_w and viewer.scroll_text_w.scrollToBottom then viewer.scroll_text_w:scrollToBottom() end
end

function Tank:followUp(messages)
    local dialog
    dialog = InputDialog:new{
        title = _("Ask a follow-up"),
        allow_newline = true,
        buttons = {{
            { text = _("Cancel"), id = "close", callback = function() UIManager:close(dialog) end },
            { text = _("Send"), is_enter_default = true, callback = function()
                local q = dialog:getInputText()
                UIManager:close(dialog)
                if q and q:match("%S") then
                    table.insert(messages, { role = "user", content = q })
                    self:send(messages)
                end
            end },
        }},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end

function Tank:editApi(id)
    local s = apiSettings(id)
    local dialog
    dialog = MultiInputDialog:new{
        title = T(_("%1 settings"), s.preset.name),
        fields = {
            { description = _("Address"), text = s.base, hint = s.preset.base },
            { description = _("API key (stays on this device)"), text = s.key, hint = s.preset.key and _("required") or _("usually not needed"), text_type = "password" },
            { description = _("Model"), text = s.model, hint = s.preset.model ~= "" and s.preset.model or _("e.g. qwen/qwen3-4b-2507") },
        },
        buttons = {{
            { text = _("Cancel"), id = "close", callback = function() UIManager:close(dialog) end },
            { text = _("Save"), callback = function()
                local f = dialog:getFields()
                local c = config()
                c.api = c.api or {}
                c.api[id] = { base = util.trim(f[1] or ""), key = util.trim(f[2] or ""), model = util.trim(f[3] or "") }
                saveConfig(c)
                UIManager:close(dialog)
            end },
        }},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end

function Tank:addToMainMenu(menu_items)
    local choices = {}
    local ids = {}
    if Device:isAndroid() then table.insert(ids, "app") end
    for _, p in ipairs(PRESETS) do table.insert(ids, p.id) end
    for _, id in ipairs(ids) do
        table.insert(choices, {
            text = id == "app" and _("Tank app: ChatGPT/Claude website, no API key") or backendName(id),
            checked_func = function() return backend() == id end,
            radio = true,
            callback = function()
                local c = config()
                c.backend = id
                saveConfig(c)
            end,
        })
    end
    menu_items.tank = {
        text = _("Tank (AI reading companion)"),
        sorting_hint = "search",
        sub_item_table = {
            {
                text_func = function() return T(_("Answer with: %1"), backendName(backend())) end,
                sub_item_table = choices,
            },
            {
                text_func = function() return T(_("AI settings: %1"), backendName(backend())) end,
                enabled_func = function() return backend() ~= "app" end,
                callback = function() self:editApi(backend()) end,
            },
            {
                text = _("Show this book's chat"),
                enabled_func = function() return backend() ~= "app" end,
                callback = function() self:showChat() end,
            },
            {
                text = _("Clear this book's chat"),
                enabled_func = function() return backend() ~= "app" end,
                keep_menu_open = true,
                callback = function()
                    self:saveMessages(nil)
                    UIManager:show(InfoMessage:new{ text = _("Chat cleared."), timeout = 2 })
                end,
            },
        },
    }
end

return Tank
