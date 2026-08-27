#!/usr/bin/env python3
"""
Fix Minecraft 26.1.x API breaking changes in Java source files.

Changes:
1. ResourceLocation -> Identifier (full qualified and imports)
2. player.getServer() -> player.level().getServer() (when called on ServerPlayer/Player/Entity)
3. CommandSourceStack.hasPermission(int) -> uses new permission system

These changes are needed because Minecraft 26.1.x renamed:
- net.minecraft.resources.ResourceLocation -> net.minecraft.resources.Identifier
- Removed Entity.getServer() (use Entity.level().getServer() instead)
- Replaced CommandSourceStack.hasPermission(int) with PermissionSet system
"""
import os
import re
import sys

# Files to process
SOURCE_DIRS = ['src/main/java', 'src/test/java']

def fix_resource_location(content, filepath):
    """Replace ResourceLocation with Identifier throughout the file."""
    # Replace full qualified name
    content = content.replace('net.minecraft.resources.ResourceLocation', 'net.minecraft.resources.Identifier')
    # Replace bare class name in imports and usage
    content = re.sub(r'\bResourceLocation\b', 'Identifier', content)
    return content

def fix_get_server_on_player(content, filepath):
    """
    Replace player.getServer() with player.level().getServer().
    This applies to:
    - player.getServer()
    - this.player.getServer()
    - <name>.getServer() where name is likely a ServerPlayer
    """
    # Common patterns seen in codebase:
    # player.getServer().execute(...)
    # this.player.getServer().execute(...)
    # We replace .getServer() with .level().getServer() only when preceded by player/this.player
    patterns = [
        # this.player.getServer() -> this.player.level().getServer()
        (r'\bthis\.player\.getServer\(\)', 'this.player.level().getServer()'),
        # player.getServer() -> player.level().getServer()
        (r'\bplayer\.getServer\(\)', 'player.level().getServer()'),
        # receiver.getServer() - common in API code where receiver is a ServerPlayer param
        # but be conservative: only handle 'player' and 'this.player' explicitly
    ]
    for pattern, replacement in patterns:
        content = re.sub(pattern, replacement, content)
    return content

def fix_component_append(content, filepath):
    """
    Fix Component.append() calls.

    The issue: TextUtil methods return Component (interface), not MutableComponent,
    so .append(Component) is not visible to the compiler.

    Fix strategy:
    - For variable assignments like `Component x = ...; x.append(...)`, cast to MutableComponent
    - For chained calls like `someMethod().append(...)`, the someMethod() must return MutableComponent
    - Simplest fix: change `Component var =` to `MutableComponent var =` when var.append() is used

    However, this is complex. A simpler approach: change TextUtil return types to MutableComponent.
    But we don't want to break the TextUtil API for callers.

    Best approach: cast at call sites that need chaining.
    Pattern: `Component x = something; ... x.append(...)` -> `MutableComponent x = something; ...`

    Alternative for inline chains: `Component.literal("a").append("b")` already works
    because Component.literal returns MutableComponent.
    """
    # Simple fix: change variable declarations from `Component msg =` to `MutableComponent msg =`
    # when followed by `.append()` calls
    # This is conservative and only triggers when we see `Component <name> =` followed later by `<name>.append(`

    # Find lines like: Component msg = ...
    # Then check if the same variable has .append() called on it later in the file
    # If yes, change Component to MutableComponent

    lines = content.split('\n')
    # Find all `Component <name> =` declarations
    decl_pattern = re.compile(r'^(\s*)Component\s+(\w+)\s*=')
    # Track all variable names declared as Component
    component_vars = set()
    for line in lines:
        m = decl_pattern.match(line)
        if m:
            component_vars.add(m.group(2))

    # For each, check if .append( is called on it
    vars_needing_mutable = set()
    for var_name in component_vars:
        # Look for `varName.append(` not preceded by a dot (to avoid matching x.varName.append)
        append_pattern = re.compile(r'(?<!\.)\b' + re.escape(var_name) + r'\.append\(')
        for line in lines:
            if append_pattern.search(line):
                vars_needing_mutable.add(var_name)
                break

    # Now replace `Component <varname> =` with `MutableComponent <varname> =` for those vars
    if vars_needing_mutable:
        # Also need to add the import if not present
        for var_name in vars_needing_mutable:
            pattern = re.compile(r'^(\s*)Component\s+(' + re.escape(var_name) + r')\s*=')
            for i, line in enumerate(lines):
                m = pattern.match(line)
                if m:
                    lines[i] = pattern.sub(r'\1MutableComponent \2 =', line)

        # Add import for MutableComponent if not present
        content = '\n'.join(lines)
        if 'import net.minecraft.network.chat.MutableComponent' not in content:
            # Add after Component import if present, else after package
            if 'import net.minecraft.network.chat.Component;' in content:
                content = content.replace(
                    'import net.minecraft.network.chat.Component;',
                    'import net.minecraft.network.chat.Component;\nimport net.minecraft.network.chat.MutableComponent;'
                )
            else:
                # Add after package line
                content = re.sub(
                    r'(package [^;]+;)',
                    r'\1\n\nimport net.minecraft.network.chat.MutableComponent;',
                    content,
                    count=1
                )

    return content

def fix_has_permission(content, filepath):
    """
    Fix CommandSourceStack.hasPermission(int) calls.

    In 26.1.x:
    - source.hasPermission(int) was removed
    - Now uses source.permissions().hasPermission(Permission) where Permission is an enum
    - However, for OP level checks, the new system uses PermissionLevel enum

    For backward compatibility, we use a helper: source.hasPermission(level)
    -> need to find the 26.1.x equivalent

    Actually, looking at the API: PermissionSet has hasPermission(Permission) where Permission
    is an enum (likely with OP_LEVEL_0..4 or similar).

    The simplest fix: replace source.hasPermission(N) with a call that works.
    But without knowing the exact 26.1.x Permission enum, we cannot fix this automatically.

    Conservative fix: comment out hasPermission calls and add TODO markers.
    Or: cast source to ServerPlayer and use player.hasPermission(int) (which might still work
    on Player class if it wasn't migrated).
    """
    # Check if Player still has hasPermission(int)
    # Based on inspection, Player might still have it (we didn't check)
    # Be safe: only comment out if on CommandSourceStack
    # The pattern is: source.hasPermission(N)
    # Replace with: source.hasPermission(N) -- but mark with TODO if needed
    # For now, leave it and let compile errors show us where to fix manually
    return content

def process_file(filepath):
    """Process a single Java file. Returns True if changes were made."""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            original = f.read()
    except UnicodeDecodeError:
        with open(filepath, 'r', encoding='latin-1') as f:
            original = f.read()

    content = original
    content = fix_resource_location(content, filepath)
    content = fix_get_server_on_player(content, filepath)
    content = fix_component_append(content, filepath)
    # Skip has_permission for now - needs manual review

    if content != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return True
    return False

def main():
    changed = 0
    total = 0
    for root in SOURCE_DIRS:
        if not os.path.isdir(root):
            continue
        for dirpath, _, filenames in os.walk(root):
            for fn in filenames:
                if not fn.endswith('.java'):
                    continue
                total += 1
                path = os.path.join(dirpath, fn)
                if process_file(path):
                    changed += 1
                    print(f"[ok] fixed: {path}")
    print(f"\nDone. Fixed {changed} of {total} Java files.")

if __name__ == '__main__':
    main()
