//! OpenVINO's C API (2026.4), only what Kokoro's fast path needs (see `fast.rs`).
//!
//! The library is opened at run time from `lib/openvino` beside the server, so a server installed
//! without it starts as before and speaks through sherpa-onnx alone. Models take float32 tensors;
//! one [Request] runs on one thread at a time, and a [Compiled] model hands out as many as wanted.

use std::ffi::{c_char, c_void, CStr, CString};
use std::path::Path;
use std::sync::OnceLock;

#[cfg(target_os = "macos")]
const LIBRARY: &str = "libopenvino_c.dylib";
#[cfg(not(target_os = "macos"))]
const LIBRARY: &str = "libopenvino_c.so";

/// `ov_element_type_e::F32` in openvino/c/ov_common.h.
const F32: u32 = 4;

extern "C" {
    fn dlopen(filename: *const c_char, flags: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlerror() -> *const c_char;
}

/// RTLD_NOW | RTLD_LOCAL.
#[cfg(target_os = "macos")]
const DLOPEN_FLAGS: i32 = 2 | 4;
#[cfg(not(target_os = "macos"))]
const DLOPEN_FLAGS: i32 = 2;

/// `ov_shape_t`.
#[repr(C)]
struct Shape {
    rank: i64,
    dims: *mut i64,
}

/// `ov_version_t`.
#[repr(C)]
struct Version {
    build_number: *const c_char,
    description: *const c_char,
}

/// The OpenVINO release whose C API layout [Api::release_memory] relies on; setup.sh installs it.
const RELEASE_MEMORY_VERSION: &str = "2026.4.";

/// `ov::CompiledModel::release_memory()`, which the C API does not wrap (see [Compiled::release_memory]).
const RELEASE_MEMORY: &CStr = c"_ZN2ov13CompiledModel14release_memoryEv";

/// `ov_property_t`: a key and its value, both C strings here.
#[repr(C)]
struct Property {
    key: *const c_char,
    value: *const c_void,
}

type Handle = *mut c_void;

/// The functions, by their names in the headers without the `ov_` prefix.
struct Api {
    core_create: unsafe extern "C" fn(*mut Handle) -> i32,
    core_read_model: unsafe extern "C" fn(Handle, *const c_char, *const c_char, *mut Handle) -> i32,
    core_compile_model_props: unsafe extern "C" fn(Handle, Handle, *const c_char, usize, *const Property, *mut Handle) -> i32,
    compiled_model_create_infer_request: unsafe extern "C" fn(Handle, *mut Handle) -> i32,
    infer_request_set_tensor: unsafe extern "C" fn(Handle, *const c_char, Handle) -> i32,
    infer_request_infer: unsafe extern "C" fn(Handle) -> i32,
    infer_request_get_output_tensor_by_index: unsafe extern "C" fn(Handle, usize, *mut Handle) -> i32,
    tensor_create_from_host_ptr: unsafe extern "C" fn(u32, Shape, *mut c_void, *mut Handle) -> i32,
    tensor_get_shape: unsafe extern "C" fn(Handle, *mut Shape) -> i32,
    tensor_get_element_type: unsafe extern "C" fn(Handle, *mut u32) -> i32,
    tensor_data: unsafe extern "C" fn(Handle, *mut *mut c_void) -> i32,
    shape_free: unsafe extern "C" fn(*mut Shape) -> i32,
    tensor_free: unsafe extern "C" fn(Handle),
    infer_request_free: unsafe extern "C" fn(Handle),
    compiled_model_free: unsafe extern "C" fn(Handle),
    model_free: unsafe extern "C" fn(Handle),
    core_free: unsafe extern "C" fn(Handle),
    get_last_err_msg: unsafe extern "C" fn() -> *const c_char,
    get_openvino_version: unsafe extern "C" fn(*mut Version) -> i32,
    version_free: unsafe extern "C" fn(*mut Version),
    /// A C++ method taking the `ov::CompiledModel` as its only argument; None on another release.
    release_memory: Option<unsafe extern "C-unwind" fn(*mut c_void)>,
}

// SAFETY: plain function pointers into a library that stays loaded for the life of the process.
unsafe impl Send for Api {}
unsafe impl Sync for Api {}

fn dl_error() -> String {
    // SAFETY: dlerror returns NULL or a NUL-terminated string valid until the next dl call.
    let message = unsafe { dlerror() };
    if message.is_null() {
        "unknown error".to_string()
    } else {
        // SAFETY: checked non-null above.
        unsafe { CStr::from_ptr(message) }.to_string_lossy().into_owned()
    }
}

impl Api {
    fn open(dir: &Path) -> Result<Api, String> {
        let path = dir.join(LIBRARY);
        if !path.is_file() {
            return Err(format!("{} is not installed", path.display()));
        }
        let name = CString::new(path.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        // SAFETY: name is NUL-terminated. The handle is never closed.
        let library = unsafe { dlopen(name.as_ptr(), DLOPEN_FLAGS) };
        if library.is_null() {
            return Err(format!("could not open {}: {}", path.display(), dl_error()));
        }
        macro_rules! api {
            ($($field:ident),*) => {
                Api {
                    release_memory: None,
                    $($field: {
                        let symbol = CString::new(concat!("ov_", stringify!($field))).unwrap();
                        // SAFETY: library is a live handle and symbol is NUL-terminated.
                        let address = unsafe { dlsym(library, symbol.as_ptr()) };
                        if address.is_null() {
                            return Err(format!("{} has no ov_{}", path.display(), stringify!($field)));
                        }
                        // SAFETY: the symbol is the C function whose signature the field declares,
                        // and a function pointer has a data pointer's size and layout on Linux and macOS.
                        unsafe { std::ptr::read((&address as *const *mut c_void).cast()) }
                    }),*
                }
            };
        }
        let mut api = api!(
            core_create,
            core_read_model,
            core_compile_model_props,
            compiled_model_create_infer_request,
            infer_request_set_tensor,
            infer_request_infer,
            infer_request_get_output_tensor_by_index,
            tensor_create_from_host_ptr,
            tensor_get_shape,
            tensor_get_element_type,
            tensor_data,
            shape_free,
            tensor_free,
            infer_request_free,
            compiled_model_free,
            model_free,
            core_free,
            get_last_err_msg,
            get_openvino_version,
            version_free
        );
        if api.version().starts_with(RELEASE_MEMORY_VERSION) {
            // SAFETY: library is a live handle; dlsym also searches libopenvino, which it loaded.
            let address = unsafe { dlsym(library, RELEASE_MEMORY.as_ptr()) };
            if !address.is_null() {
                // SAFETY: a non-static C++ method with no arguments and no result takes `this` as its
                // only argument (Itanium ABI, used on Linux and macOS).
                api.release_memory = Some(unsafe { std::ptr::read((&address as *const *mut c_void).cast()) });
            }
        }
        Ok(api)
    }

    /// The library's build number, such as "2026.4.1-20914-…"; empty if it cannot say.
    fn version(&self) -> String {
        let mut version = Version { build_number: std::ptr::null(), description: std::ptr::null() };
        // SAFETY: version is writable and freed below only when filled in.
        if unsafe { (self.get_openvino_version)(&mut version) } != 0 || version.build_number.is_null() {
            return String::new();
        }
        // SAFETY: checked non-null; the string lives until version_free.
        let number = unsafe { CStr::from_ptr(version.build_number) }.to_string_lossy().into_owned();
        // SAFETY: filled in by get_openvino_version, freed exactly once.
        unsafe { (self.version_free)(&mut version) };
        number
    }

    /// Turns a status code into a result, with OpenVINO's own message.
    fn check(&self, status: i32, what: &str) -> Result<(), String> {
        if status == 0 {
            return Ok(());
        }
        // SAFETY: returns NULL or a NUL-terminated string owned by the library.
        let message = unsafe { (self.get_last_err_msg)() };
        let detail = if message.is_null() {
            format!("status {status}")
        } else {
            // SAFETY: checked non-null above.
            unsafe { CStr::from_ptr(message) }.to_string_lossy().trim().to_string()
        };
        Err(format!("OpenVINO could not {what}: {detail}"))
    }
}

/// The library, opened once per process. A second folder is never tried: the server has one.
fn api(dir: &Path) -> Result<&'static Api, String> {
    static API: OnceLock<Result<Api, String>> = OnceLock::new();
    API.get_or_init(|| Api::open(dir)).as_ref().map_err(Clone::clone)
}

/// Whether the library is installed in [dir].
pub fn installed(dir: &Path) -> bool {
    dir.join(LIBRARY).is_file()
}

/// An OpenVINO runtime. Models compiled from it should be dropped before it.
pub struct Core {
    api: &'static Api,
    raw: Handle,
}

/// A model compiled for the CPU.
pub struct Compiled {
    api: &'static Api,
    raw: Handle,
}

/// One inference in progress on a [Compiled] model; reusable, one thread at a time.
pub struct Request {
    api: &'static Api,
    raw: Handle,
}

/// A tensor a request produced. Its memory belongs to that request and changes on its next run.
pub struct Tensor {
    api: &'static Api,
    raw: Handle,
}

// SAFETY: OpenVINO's core and compiled models may be used from several threads at once; a request
// and its tensors move between threads but are used by one at a time (`&mut self` to run).
unsafe impl Send for Core {}
unsafe impl Sync for Core {}
unsafe impl Send for Compiled {}
unsafe impl Sync for Compiled {}
unsafe impl Send for Request {}
unsafe impl Send for Tensor {}

impl Core {
    pub fn open(library_dir: &Path) -> Result<Core, String> {
        let api = api(library_dir)?;
        let mut raw: Handle = std::ptr::null_mut();
        // SAFETY: raw is a writable handle.
        api.check(unsafe { (api.core_create)(&mut raw) }, "start")?;
        Ok(Core { api, raw })
    }

    /// Reads the model at [path] (ONNX) and compiles it for the CPU with [properties].
    pub fn compile(&self, path: &Path, properties: &[(&str, &str)]) -> Result<Compiled, String> {
        let api = self.api;
        let file = CString::new(path.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        let mut model: Handle = std::ptr::null_mut();
        // SAFETY: self.raw is live; file is NUL-terminated; a null second path means "none".
        api.check(
            unsafe { (api.core_read_model)(self.raw, file.as_ptr(), std::ptr::null(), &mut model) },
            &format!("read {}", path.display()),
        )?;
        let strings: Vec<(CString, CString)> = properties
            .iter()
            .map(|(key, value)| (CString::new(*key).unwrap_or_default(), CString::new(*value).unwrap_or_default()))
            .collect();
        let pairs: Vec<Property> =
            strings.iter().map(|(key, value)| Property { key: key.as_ptr(), value: value.as_ptr().cast() }).collect();
        let device = CString::new("CPU").unwrap();
        let mut raw: Handle = std::ptr::null_mut();
        // SAFETY: every pointer outlives the call; pairs holds pairs.len() properties.
        let status = unsafe { (api.core_compile_model_props)(self.raw, model, device.as_ptr(), pairs.len(), pairs.as_ptr(), &mut raw) };
        // SAFETY: model is live and freed exactly once; the compiled model keeps what it needs.
        unsafe { (api.model_free)(model) };
        api.check(status, &format!("compile {}", path.display()))?;
        Ok(Compiled { api, raw })
    }
}

impl Compiled {
    /// Frees the memory the model keeps between runs: buffers sized for the longest input it has
    /// run, which with Kokoro's dynamic lengths reach 1-2 GB after one long sentence and are never
    /// given back otherwise. The next run allocates what it needs again (a few ms). Returns false
    /// where this release of OpenVINO does not offer it.
    ///
    /// No request of this model may be running: OpenVINO throws then, which would abort the server.
    pub fn release_memory(&self) -> bool {
        let Some(release) = self.api.release_memory else { return false };
        // SAFETY: on the release checked in Api::open, `ov_compiled_model_t` is
        // `{ std::shared_ptr<ov::CompiledModel> object; }` and a shared_ptr's first word is the
        // object it points to. The caller guarantees no request is running.
        unsafe {
            let object = *(self.raw as *const *mut c_void);
            release(object);
        }
        true
    }

    pub fn request(&self) -> Result<Request, String> {
        let mut raw: Handle = std::ptr::null_mut();
        // SAFETY: self.raw is live; raw is a writable handle.
        self.api.check(unsafe { (self.api.compiled_model_create_infer_request)(self.raw, &mut raw) }, "create a request")?;
        Ok(Request { api: self.api, raw })
    }
}

impl Request {
    /// Points the input named [name] at [data], shaped [dims]. The data is not copied: it must stay
    /// alive and unchanged until [run] returns.
    pub fn set_input(&mut self, name: &CStr, dims: &[i64], data: &[f32]) -> Result<(), String> {
        let count: i64 = dims.iter().product();
        if count < 0 || count as usize != data.len() {
            return Err(format!("{} values do not fill the shape {dims:?}", data.len()));
        }
        let mut dims = dims.to_vec();
        let shape = Shape { rank: dims.len() as i64, dims: dims.as_mut_ptr() };
        let mut tensor: Handle = std::ptr::null_mut();
        // SAFETY: shape points at dims.len() dimensions for the call; data holds their product in
        // floats. OpenVINO only reads an input tensor.
        self.api.check(
            unsafe { (self.api.tensor_create_from_host_ptr)(F32, shape, data.as_ptr() as *mut c_void, &mut tensor) },
            "wrap an input",
        )?;
        let tensor = Tensor { api: self.api, raw: tensor };
        self.set_input_tensor(name, &tensor)
    }

    /// Feeds another request's output as the input named [name], without copying it.
    pub fn set_input_tensor(&mut self, name: &CStr, tensor: &Tensor) -> Result<(), String> {
        // SAFETY: both handles are live; the request keeps its own reference to the tensor.
        self.api.check(unsafe { (self.api.infer_request_set_tensor)(self.raw, name.as_ptr(), tensor.raw) }, "set an input")
    }

    pub fn run(&mut self) -> Result<(), String> {
        // SAFETY: self.raw is live and used by this thread alone.
        self.api.check(unsafe { (self.api.infer_request_infer)(self.raw) }, "run the model")
    }

    /// The model's first output, after [run].
    pub fn output(&self) -> Result<Tensor, String> {
        let mut raw: Handle = std::ptr::null_mut();
        // SAFETY: self.raw is live; raw is a writable handle.
        self.api.check(unsafe { (self.api.infer_request_get_output_tensor_by_index)(self.raw, 0, &mut raw) }, "read the output")?;
        Ok(Tensor { api: self.api, raw })
    }
}

impl Tensor {
    /// The tensor's values. Valid until the request that produced it runs again, which borrowing
    /// the tensor does not prevent: read them before that.
    pub fn floats(&self) -> Result<&[f32], String> {
        let api = self.api;
        let mut kind = 0u32;
        // SAFETY: self.raw is live; kind is writable.
        api.check(unsafe { (api.tensor_get_element_type)(self.raw, &mut kind) }, "read the output's type")?;
        if kind != F32 {
            return Err(format!("OpenVINO returned element type {kind}, not float32"));
        }
        let mut shape = Shape { rank: 0, dims: std::ptr::null_mut() };
        // SAFETY: self.raw is live; shape is writable and freed below.
        api.check(unsafe { (api.tensor_get_shape)(self.raw, &mut shape) }, "read the output's shape")?;
        let mut count = 1usize;
        for index in 0..shape.rank.max(0) as usize {
            // SAFETY: dims holds rank dimensions.
            count = count.saturating_mul(unsafe { *shape.dims.add(index) }.max(0) as usize);
        }
        // SAFETY: shape came from tensor_get_shape and is freed exactly once.
        unsafe { (api.shape_free)(&mut shape) };
        if count == 0 {
            return Ok(&[]);
        }
        let mut data: *mut c_void = std::ptr::null_mut();
        // SAFETY: self.raw is live; data is writable.
        api.check(unsafe { (api.tensor_data)(self.raw, &mut data) }, "read the output's data")?;
        if data.is_null() {
            return Err("OpenVINO returned no data".to_string());
        }
        // SAFETY: a float32 tensor holds `count` floats at `data` (see the lifetime note above).
        Ok(unsafe { std::slice::from_raw_parts(data as *const f32, count) })
    }
}

impl Drop for Tensor {
    fn drop(&mut self) {
        // SAFETY: freed exactly once; the request keeps its own reference to the memory.
        unsafe { (self.api.tensor_free)(self.raw) };
    }
}

impl Drop for Request {
    fn drop(&mut self) {
        // SAFETY: freed exactly once.
        unsafe { (self.api.infer_request_free)(self.raw) };
    }
}

impl Drop for Compiled {
    fn drop(&mut self) {
        // SAFETY: freed exactly once.
        unsafe { (self.api.compiled_model_free)(self.raw) };
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        // SAFETY: freed exactly once.
        unsafe { (self.api.core_free)(self.raw) };
    }
}
