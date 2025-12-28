var currentImage = 0;
var scale_x, scale_y;
var wm_x = 0, wm_y = 0;
var mouse_x, mouse_y;
var imageInput = document.getElementById('inputImage')
var watermarkInput = document.getElementById('inputWatermark')
var image = document.getElementById('image')
var watermark = document.getElementById('watermark')
var wm_img_load
var rtPrev1 = document.getElementById('rtPrev1'), rtPrev2 = document.getElementById('rtPrev2')
var rtPrevBox = document.getElementById('rtPrevBox')
var image_ib, wm_ib
var imageContainer = document.getElementById('imageContainer')
var zoomIndicator = document.getElementById('zoom')
var extras_dropdown = document.getElementById('extras')
var imageBackupUrl = ''
var files
var zoomlevel = 100
var filters = {
    contrast: 1,
    brightness: 1
}
var zipFile
var isBatchZip = false
var wmPosIsUpdate = false   //for automatic positioning in batch mode

//TEST: workaround for missing Uint8Array.fromBase64
Uint8Array.fromBase64 = undefined
var PRESETS, currentPreset, localFileStorageHandle

imageInput.addEventListener('change', function() {
    window.setTimeout(()=>this.blur(), 10)
    loadImageFromInput()
})
watermarkInput.addEventListener('change', function() {
    window.setTimeout(()=>this.blur(), 10)
    let url = URL.createObjectURL(watermarkInput.files[0])
    loadWatermark(url)
})
document.addEventListener('drop', loadImageFromDrop)
document.addEventListener('dragover', e => e.preventDefault())
image.addEventListener('dragstart', e => e.preventDefault())
watermark.addEventListener('pointerdown', dragWatermarkStart)
watermark.addEventListener('touchmove', e => e.preventDefault())
document.addEventListener('keydown', moveWatermark)
document.getElementById('overlayMode').addEventListener('change', function(){
    setOverlayMode(this.value)
})

function loadImage(useThisUrlInstead) {
    if (files.length > currentImage) {
        image.onload = async function () {
            if (this.naturalHeight > 32000 || this.naturalWidth > 32000 || this.naturalWidth*this.naturalHeight > 250000000) {
                alert('Image is too large. (Browser limitation)')
                this.width = 0
                this.height = 0
            }
            //restore/update watermark position.
            //Visual updates happen as consequence of resetZoom.
            if (wmPosIsUpdate) {
                switch (document.getElementById('defaultPosition').value) {
                    case 'top-left':
                        break;
                    case 'top-right':
                        wm_x = image.naturalWidth - wm_x
                        break;
                    case 'center':
                        wm_x = wm_x + image.naturalWidth / 2
                        wm_y = wm_y + image.naturalHeight / 2
                        break;
                    case 'bottom-left':
                        wm_y = image.naturalHeight - wm_y
                        break;
                    case 'bottom-right':
                        wm_x = image.naturalWidth - wm_x
                        wm_y = image.naturalHeight - wm_y
                        break;
                }
                wmPosIsUpdate = false
            }
            resetZoom()
            image_ib = await createImageBitmap(this, {
                colorSpaceConversion: 'none',
                premultiplyAlpha: 'none',
                resizeQuality: 'pixelated'
            })
            this.onload = undefined
            if (extras_on_image_load.hasOwnProperty(extras_dropdown.value)) {
                //workaround async execution
                console.debug('calling extra')
                await new Promise((resolve, reject) => {extras_on_image_load[extras_dropdown.value](resolve)})
                console.debug('called extra')
            }
            if (currentImage > 0 && document.getElementById('fullBatch').checked) {
                console.debug('calling un-wm')
                unwatermark()
                console.debug('called un-wm')
            }
        }
        if (useThisUrlInstead) {
            image.src = useThisUrlInstead
        } else {
            if (image.src.startsWith('blob:')) {
                URL.revokeObjectURL(image.src)
            }
            image.src = URL.createObjectURL(files[currentImage])
            if (currentImage == 0) {
                if (files.length > 1) {
                    //enable skipbutton for batch mode.
                    document.getElementById('skipbutton').style.display = 'initial'
                } else {
                    //disable the button when batch mode is interrupted
                    document.getElementById('skipbutton').style.display = 'none'
                }
            }
        }
    } else {
        //alert('No more images loaded.')
    }
}
function loadImageFromInput() {
    files = imageInput.files
    currentImage = 0
    loadImage()
}
function loadImageFromDrop(ev) {
    // Prevent default behavior (Prevent file from being opened)
    ev.preventDefault();

    if (ev.dataTransfer.items) {
        // Use DataTransferItemList interface to access the file(s)
        files = []
        for (var i = 0; i < ev.dataTransfer.items.length; i++) {
        // If dropped items aren't files, reject them
            if (ev.dataTransfer.items[i].kind === 'file') {
                files.push(ev.dataTransfer.items[i].getAsFile())
            }
        }
    } else {
        // Use DataTransfer interface to access the file(s)
        files = ev.dataTransfer.files
    }
    currentImage = 0
    loadImage()
}
function loadWatermark(url) {
    return new Promise((resolve, reject) => {
        if(scale_x && scale_y) {
            wm_img_load = document.createElement('img')
            wm_img_load.onload = async function () {
                //print to the watermark canvas
                watermark.width = wm_img_load.naturalWidth
                watermark.height = wm_img_load.naturalHeight
                wm_ib = await createImageBitmap(this, {
                    colorSpaceConversion: 'none',
                    premultiplyAlpha: 'none',
                    resizeQuality: 'pixelated'
                })
                let ctx = watermark.getContext('2d')
                ctx.drawImage(wm_ib, 0, 0)
                rtPrev1.width = watermark.width
                rtPrev1.height = watermark.height
                rtPrev2.width = watermark.width
                rtPrev2.height = watermark.height
                let cx1 = rtPrev1.getContext('2d')
                cx1.fillStyle = '#000'
                cx1.fillRect(0,0, watermark.width, watermark.height)
                cx1.drawImage(wm_ib, 0, 0)
                let cx2 = rtPrev2.getContext('2d')
                cx2.drawImage(wm_ib, 0, 0)
                cx2.fillStyle = '#fff'
                cx2.globalCompositeOperation = 'source-in'
                cx2.fillRect(0,0, watermark.width, watermark.height)
                cx2.fillStyle = '#000'
                cx2.globalCompositeOperation = 'destination-over'
                cx2.fillRect(0,0, watermark.width, watermark.height)
                //default position
                var position = document.getElementById('defaultPosition').value
                if (position == 'center') {
                    wm_x = Math.round((image.naturalWidth - wm_img_load.naturalWidth) / 2)
                    wm_y = Math.round((image.naturalHeight - wm_img_load.naturalHeight) / 2)
                } else {
                    if (position.indexOf('right') > -1) {
                        wm_x = image.naturalWidth - wm_img_load.naturalWidth
                    } else {
                        wm_x = 0
                    }
                    if (position.indexOf('bottom') > -1) {
                        wm_y = image.naturalHeight - wm_img_load.naturalHeight
                    } else {
                        wm_y = 0
                    }
                }
                let x = String(scale_x * wm_img_load.naturalWidth) + 'px'
                let y = String(scale_y * wm_img_load.naturalHeight) + 'px'
                watermark.style.width = x
                watermark.style.height = y
                rtPrev1.style.width = x
                rtPrev1.style.height = y
                rtPrev2.style.width = x
                rtPrev2.style.height = y
                rtPrevBox.style.width = x
                rtPrevBox.style.height = y
                updateWatermarkPosition()
                resolve()
            }
            wm_img_load.src = url
        } else {
            alert('Load image first.')
            reject()
        }
    })
}
function updateWatermarkPosition() {
    if (!wm_ib) return;
    let y = String(wm_y * scale_y) + 'px'
    let x = String(wm_x * scale_x) + 'px'
    watermark.style.top = y
    watermark.style.left = x
    rtPrev1.style.top = y
    rtPrev1.style.left = x
    rtPrev2.style.top = y
    rtPrev2.style.left = x
    rtPrevBox.style.top = y
    rtPrevBox.style.left = x

    let anchor = document.getElementById('defaultPosition').value || 'top-left'
    switch (anchor) {
        case 'top-left':
        case 'center-left':
        case 'bottom-left':
            x = wm_x
            break
        case 'top-center':
        case 'center':
        case 'bottom-center':
            x = wm_x - Math.round((image.naturalWidth - wm_ib.width) * 0.5)
            break
        case 'top-right':
        case 'center-right':
        case 'bottom-right':
            x = wm_x - (image.naturalWidth - wm_ib.width)
            break
    }
    document.getElementById('relPosX').innerText = x
    //now y
    if (anchor.startsWith('top')) {
        y = wm_y
    }
    if (anchor.startsWith('center')) {
        y = wm_y - Math.round((image.naturalHeight - wm_ib.height) * 0.5)
    }
    if (anchor.startsWith('bottom')) {
        y = wm_y - (image.naturalHeight - wm_ib.height)
    }
    document.getElementById('relPosY').innerText = y
}
function updateWatermarkStyle() {
    let y = String(watermark.height * scale_y) + 'px'
    let x = String(watermark.width * scale_x) + 'px'
    watermark.style.width = x
    watermark.style.height = y
    rtPrev1.style.width = x
    rtPrev1.style.height = y
    rtPrev2.style.width = x
    rtPrev2.style.height = y
    rtPrevBox.style.width = x
    rtPrevBox.style.height = y
    updateWatermarkPosition()
}
function moveWatermark (e) {
    if (watermark.width != 0) {
        switch (e.keyCode) {
            case 37:
                wm_x -= e.ctrlKey ? 0.05 : 1
                break
            case 38:
                wm_y -= e.ctrlKey ? 0.05 : 1
                break
            case 39:
                wm_x += e.ctrlKey ? 0.05 : 1
                break
            case 40:
                wm_y += e.ctrlKey ? 0.05 : 1
                break
            default:
                return
            
        }
        e.preventDefault()
        updateWatermarkPosition()
    }
}
function dragWatermarkStart(e) {
    e = e || window.event;
    e.preventDefault();
    // get the mouse cursor position at startup:
    mouse_x = e.clientX;
    mouse_y = e.clientY;
    document.onpointerup = dragWatermarkEnd;
    document.onpointercancel = dragWatermarkEnd;
    // call a function whenever the cursor moves:
    document.onpointermove = dragWatermark;
}
function dragWatermark(e) {
    e = e || window.event;
    e.preventDefault();
    // calculate the new cursor position:
    wm_x -= Math.round((mouse_x - e.clientX) / scale_x);
    wm_y -= Math.round((mouse_y - e.clientY) / scale_y);
    mouse_x = e.clientX;
    mouse_y = e.clientY;
    // set the element's new position:
    updateWatermarkPosition()
}
function dragWatermarkEnd(e) {
    e.preventDefault();
    // stop moving when mouse button is released:
    document.onpointerup = null;
    document.onpointercancel = null;
    document.onpointermove = null;
}
function unwatermark() {
                console.debug('un-wm started')
    // var w = watermark.width
    // if (wm_x + w > image.naturalWidth) {
    //     w -= (wm_x + w) - image.naturalWidth
    // }
    // var h = watermark.height
    // if (wm_y + h > image.naturalHeight) {
    //     h -= (wm_y + h) - image.naturalHeight
    // }
    var x = parseInt(wm_x)
    var x_sub = wm_x % 1
    var y = parseInt(wm_y)
    var y_sub = wm_y % 1

    var w = Math.min(x + watermark.width, image.naturalWidth) - Math.max(x, 0)
    var h = Math.min(y + watermark.height, image.naturalHeight) - Math.max(y, 0)
    
    //prepare image canvas
    var c = document.createElement('canvas')
    var ctx = c.getContext('2d')
    c.width = image.naturalWidth
    c.height = image.naturalHeight
    ctx.drawImage(image_ib, 0, 0)
    
    //prepare watermark canvas
    var c_wm = document.createElement('canvas')
    var ctx_wm = c_wm.getContext('2d')
    c_wm.width = watermark.width
    c_wm.height = watermark.height
    if (x_sub || y_sub) {
        ctx_wm.translate(x_sub, y_sub)
    }
    ctx_wm.drawImage(watermark, 0, 0)
    
    //get that value because we'll need to load a bit more data from the images
    const wholePxRadius = parseInt(document.getElementById('autoWholePixel').value)
    let img_x = Math.max(0, x-wholePxRadius)
    let img_y = Math.max(0, y-wholePxRadius)
    let img_w = w //Math.min(image.naturalWidth, w+2*wholePxRadius)   //this was a failed experiment anyway
    let img_h = h //Math.min(image.naturalHeight, h+2*wholePxRadius)
    var imagedata_image = ctx.getImageData(img_x, img_y, img_w, img_h)
    var imagedata_watermark = ctx_wm.getImageData(-Math.min(x, 0), -Math.min(y, 0), w, h)
    
    var img_pixel_offset = 0
    
    const transparencyThreshold = parseInt(document.getElementById('transparencyThreshold').value)
    const opaqueThreshold = parseInt(document.getElementById('opaqueThreshold').value)
    const postprocSmoothAlphaEdges = document.getElementById('smoothEdges').checked
    const postprocAdjustBrightness = document.getElementById('adjustBrightness').checked
    const automaticSubpixelAlignment = document.getElementById('autoSubpixel').checked
    
    if (postprocSmoothAlphaEdges || automaticSubpixelAlignment || wholePxRadius) {
        var edgeThreshold = 0
        for (let i = 3; i < w*h*4; i += 12) {
            if (imagedata_watermark.data[i] > edgeThreshold) {
                edgeThreshold = imagedata_watermark.data[i]
            }
            if (i % (w*4) < 11) {
                i += w*4
            }
        }
        edgeThreshold = edgeThreshold * 0.4
    }
    
    if (automaticSubpixelAlignment || wholePxRadius) {
        //create a buffer with some points to check alignment
        const checkSize = 32
        let checkBuffer = {
            horizontal: [],
            vertical: []
        }
        outerLoop : for (let i = 3; i < w*h*4; i+=4) {
            const coord_i = [(i/4) % w, Math.floor(i/(4*w))]
            if (Math.abs(imagedata_watermark.data[i-4] - imagedata_watermark.data[i]) > edgeThreshold) {
                if (coord_i[0] - 3 < 0 || coord_i[0] + 3 >= w) {
                    //only consider those with no other edges close to them
                    continue outerLoop
                }
//                 imagedata_image.data[i-3] = 0
//                 imagedata_image.data[i-2] = 255
//                 imagedata_image.data[i-1] = 0
                checkBuffer.horizontal.push(i)
            }
            if (Math.abs(imagedata_watermark.data[i-w*4] - imagedata_watermark.data[i]) > edgeThreshold) {
                for (let ii = -3; ii <= 3; ii++) {
                    if (coord_i[1] - 3 < 0 || coord_i[1] + 3 >= h) {
                        //only use consider those with no other edges close to them
                        continue outerLoop
                    }
                }
//                 imagedata_image.data[i-3] = 255
//                 imagedata_image.data[i-2] = 0
//                 imagedata_image.data[i-1] = 255
                checkBuffer.vertical.push(i)
            }
        }
        let checkBufferCopy = {
            horizontal: checkBuffer.horizontal.slice(),
            vertical: checkBuffer.vertical.slice()
        }
        //reduce the amount of pixels to test with because the following loop is fucking awful
        for (let x in checkBuffer) {
            while (checkBuffer[x].length > 1000) {
                for (let i = 0; i < checkBuffer[x].length; i++) {
                    checkBuffer[x].splice(i, 1)
                }
            }
        }
        //awful code ahead, viewer discretion is advised
        for (let x in checkBuffer) {
            checkBuffer[x].forEach((y, i) => {
                //check if the 5x5 neighborhood includes other points
                for (let j = y - 12*(w+1); j < y + 12*(w+1); j += 4) {
                    //filter out edges/borders
                    if (checkBufferCopy[x == 'horizontal' ? 'vertical' : 'horizontal'].includes(j)) {
                        checkBuffer[x].splice(i, 1)
                        break
                    }
                    if (j%(w*4) > y%(w*4)+12) j += w*4 - 28
                }
            })
        }
        //further reduce the amount of pixels to test with
        for (let x in checkBuffer) {
            while (checkBuffer[x].length > 64) {
                for (let i = 0; i < checkBuffer[x].length; i++) {
                    checkBuffer[x].splice(i, 1)
                }
            }
        }
        if (false && wholePxRadius) {
            //check the candidate pixels for the best whole pixel alignment
            //the wholePxRadius value/setting defines the search radius
            
            //We will check each possible offset for its accumulated positioning error
            //and pick the one with the lowest
            let accumulatedOffsetErrors = []
            
            //iterate over all positions
            // can't check x and y separately since the differences could be too large
            // as opposed to the subpixel alignment
            for (let offset_x = 0; offset_x <= wholePxRadius*2; offset_x++) {
                for (let offset_y = 0; offset_y <= wholePxRadius*2; offset_y++) {
                    let offsetError = 0
                    //I don't think the axis difference matters much for this case
                    //but I can't be arsed to change that now
                    for (let axis in checkBuffer) {
                        for (const ii of checkBuffer[axis]) {
                            const i = ii - 3 //we saved the alpha's index in the checkbuffer during edge detection
                            var axisNeighborsWM, axisNeighborsImg
                            //precalc the indices for the neighboring pixels, pray we dont go off-canvas
                            //(though I don't think we will)
                            const offset_pixel_xy = 4 * (offset_x + offset_y*img_w)
                            if (axis == 'x') {
                                axisNeighborsWM = [ 
                                    i-8, 
                                    i-4, 
                                    i, 
                                    i+4, 
                                    i+8
                                ]
                                axisNeighborsImg = [ 
                                    i-8 + offset_pixel_xy, 
                                    i-4 + offset_pixel_xy, 
                                    i + offset_pixel_xy, 
                                    i+4 + offset_pixel_xy, 
                                    i+8 + offset_pixel_xy
                                ]
                            } else {
                                axisNeighborsWM = [ 
                                    i-8*w,
                                    i-4*w,
                                    i, 
                                    i+4*w, 
                                    i+8*w
                                ]
                                axisNeighborsImg = [ 
                                    i-8*img_w + offset_pixel_xy,
                                    i-4*img_w + offset_pixel_xy,
                                    i + offset_pixel_xy, 
                                    i+4*img_w + offset_pixel_xy, 
                                    i+8*img_w + offset_pixel_xy
                                ]
                            }
                            //fetch pixel data (here: alpha)
                            const pixelsWatermarkAlpha = [
                                imagedata_watermark.data[axisNeighborsWM[0]+3],
                                imagedata_watermark.data[axisNeighborsWM[1]+3],
                                imagedata_watermark.data[axisNeighborsWM[2]+3],
                                imagedata_watermark.data[axisNeighborsWM[3]+3],
                                imagedata_watermark.data[axisNeighborsWM[4]+3],
                            ]
                            //each color channel
                            //we will calculate the apparent alignment error for each channel
                            //individually. This _probably_ won't give the absolute best results,
                            //but should be good enough and is easier and more efficiently done
                            let spotError = 0
                            for (let c = 0; c < 3; c++) {
                                //get the remaining pixel data (one channel at a time)
                                const pixelsImage = [
                                    imagedata_image.data[axisNeighborsImg[0] + c],
                                    imagedata_image.data[axisNeighborsImg[1] + c],
                                    imagedata_image.data[axisNeighborsImg[2] + c],
                                    imagedata_image.data[axisNeighborsImg[3] + c],
                                    imagedata_image.data[axisNeighborsImg[4] + c]
                                ]
                                const pixelsWatermark = [
                                    imagedata_watermark.data[axisNeighborsWM[0]+c],
                                    imagedata_watermark.data[axisNeighborsWM[1]+c],
                                    imagedata_watermark.data[axisNeighborsWM[2]+c],
                                    imagedata_watermark.data[axisNeighborsWM[3]+c],
                                    imagedata_watermark.data[axisNeighborsWM[4]+c],
                                ]
                                //try how unwatermarking would look
                                let unwatermarked = new Array(5)
                                for (let j = 0; j < 5; j++) {
                                    const alpha_img = 255 / (255 - pixelsWatermarkAlpha[j])
                                    const alpha_wm = -pixelsWatermarkAlpha[j] / (255 - pixelsWatermarkAlpha[j])
                                    unwatermarked[j] = alpha_img * pixelsImage[j] + alpha_wm * pixelsWatermark[j]
                                }
                                const outerMean = (unwatermarked[0] + unwatermarked[4]) / 2
                                spotError += (unwatermarked[0] - outerMean) ** 2 +
                                    (unwatermarked[1] - outerMean) ** 2 +
                                    (unwatermarked[2] - outerMean) ** 2 +
                                    (unwatermarked[3] - outerMean) ** 2 +
                                    (unwatermarked[4] - outerMean) ** 2;
                            }
                            //with the error value for the current spot accumulated,
                            //pass it on to the accumulated offset error
                            offsetError += spotError
                        }
                    }
                    accumulatedOffsetErrors.push({
                        error: offsetError,
                        x: offset_x,
                        y: offset_y
                    })
                }
            }
            //with all errors collected, pick the lowest one
            console.log(accumulatedOffsetErrors)
            let leastError = { error: Infinity }
            for (let x of accumulatedOffsetErrors) {
                if (x.error < leastError.error) {
                    leastError = x
                }
            }
            img_pixel_offset = 4 * (leastError.x + leastError.y * img_w)
        }
        
        if (automaticSubpixelAlignment) {
            //check the candidate pixels for the best alignment
            let subpixelPositions = {
                x: [],
                y: []
            }
            for (let axis in subpixelPositions) {
                for (const ii of checkBuffer[(axis == 'x' ? 'horizontal' : 'vertical')]) {
                    const i = ii - 3 //we saved the alpha's index earlier
                    let bestSubpixel = 0
                    var axisNeighbors
                    if (axis == 'x') {
                        axisNeighbors = [ i-12, i-8, i-4, i, i+4, i+8, i+12 ]
                    } else {
                        axisNeighbors = [ i-12*w, i-8*w, i-4*w, i, i+4*w, i+8*w, i+12*w ]
                    }
                    const pixelsWatermarkAlpha = [
                        imagedata_watermark.data[axisNeighbors[0]+3],
                        imagedata_watermark.data[axisNeighbors[1]+3],
                        imagedata_watermark.data[axisNeighbors[2]+3],
                        imagedata_watermark.data[axisNeighbors[3]+3],
                        imagedata_watermark.data[axisNeighbors[4]+3],
                        imagedata_watermark.data[axisNeighbors[5]+3],
                        imagedata_watermark.data[axisNeighbors[6]+3]
                    ]
                    //each color channel
                    for (let c = 0; c < 3; c++) {
                        //for better readability
                        const pixelsImage = [
                            imagedata_image.data[axisNeighbors[1]+c],
                            imagedata_image.data[axisNeighbors[2]+c],
                            imagedata_image.data[axisNeighbors[3]+c],
                            imagedata_image.data[axisNeighbors[4]+c],
                            imagedata_image.data[axisNeighbors[5]+c]
                        ]
                        const pixelsWatermark = [
                            imagedata_watermark.data[axisNeighbors[0]+c],
                            imagedata_watermark.data[axisNeighbors[1]+c],
                            imagedata_watermark.data[axisNeighbors[2]+c],
                            imagedata_watermark.data[axisNeighbors[3]+c],
                            imagedata_watermark.data[axisNeighbors[4]+c],
                            imagedata_watermark.data[axisNeighbors[5]+c],
                            imagedata_watermark.data[axisNeighbors[6]+c]
                        ]
                        //check the subpixel positioning by unwatermarking each step end checking for the least error
                        //(not very efficient, but that's why the sample size is so small lol)
                        let bestError = Infinity
                        let bestSubpixelOfChannel = 0
                        for (let sub = -1; sub < 1; sub += 0.05) {
                            //shift the watermark by some subpixel amount using linear interpolation
                            //TODO: REFACTOR TO MAKE SUB-LOOP HIGHER LEVEL THAN CHANNEL-LOOP TO NOT RECALCULATE ALPHA VALUES
                            //but I'm too lazy and not sure if that'd even work, so it's staying for now
                            let watermarkAligned = new Array(5)
                            let watermarkAlignedAlpha = new Array(5)
                            for (let j = 0; j < 5; j++) {
                                if (sub > 0) {
                                    watermarkAligned[j] = pixelsWatermark[j+1] * (1-sub) + pixelsWatermark[j+2] * sub
                                    watermarkAlignedAlpha[j] = pixelsWatermarkAlpha[j+1] * (1-sub) + pixelsWatermarkAlpha[j+2] * sub
                                } else {
                                    watermarkAligned[j] = pixelsWatermark[j+1] * (1+sub) + pixelsWatermark[j] * (-sub)
                                    watermarkAlignedAlpha[j] = pixelsWatermarkAlpha[j+1] * (1+sub) + pixelsWatermarkAlpha[j] * (-sub)
                                }
                            }
                            //test the unwatermarked result for errors from misalignment
                            let unwatermarked = new Array(5)
                            for (let j = 0; j < 5; j++) {
                                const alpha_img = 255 / (255 - watermarkAlignedAlpha[j])
                                const alpha_wm = -watermarkAlignedAlpha[j] / (255 - watermarkAlignedAlpha[j])
                                unwatermarked[j] = alpha_img * pixelsImage[j] + alpha_wm * watermarkAligned[j]
                            }
                            const outerMean = (unwatermarked[0] + unwatermarked[4]) / 2
                            const error = (unwatermarked[0] - outerMean) ** 2 +
                                (unwatermarked[1] - outerMean) ** 2 +
                                (unwatermarked[2] - outerMean) ** 2 +
                                (unwatermarked[3] - outerMean) ** 2 +
                                (unwatermarked[4] - outerMean) ** 2
                            if (error < bestError) {
                                bestError = error
                                bestSubpixelOfChannel = sub
                            }
                        }
                        bestSubpixel += bestSubpixelOfChannel
                    }
                    subpixelPositions[axis].push(bestSubpixel/3)
    //                 if (axis == 'x') {
    //                     imagedata_image.data[ii-3] = 0
    //                     imagedata_image.data[ii-2] = 255
    //                     imagedata_image.data[ii-1] = 0
    //                 } else {
    //                     imagedata_image.data[ii-3] = 255
    //                     imagedata_image.data[ii-2] = 0
    //                     imagedata_image.data[ii-1] = 255
    //                 }
                }
            }
            a = subpixelPositions
            let sub_x = 0, sub_y = 0
            subpixelPositions.x.sort((a, b) => a - b)
            for (let i = Math.round(subpixelPositions.x.length * 0.25); i < Math.round(subpixelPositions.x.length * 0.75); i++)
                sub_x += subpixelPositions.x[i]
            sub_x = sub_x / Math.round(subpixelPositions.x.length / 2)
            subpixelPositions.y.sort((a, b) => a - b)
            for (let i = Math.round(subpixelPositions.y.length * 0.25); i < Math.round(subpixelPositions.y.length * 0.75); i++)
                sub_y += subpixelPositions.y[i]
            sub_y = sub_y / Math.round(subpixelPositions.y.length / 2)
            console.log(sub_x, sub_y)
            ctx_wm.clearRect(0, 0, w, h)
            ctx_wm.translate(-sub_x, -sub_y)
            ctx_wm.drawImage(watermark, 0, 0)
            imagedata_watermark = ctx_wm.getImageData(-Math.min(x, 0), -Math.min(y, 0), w, h)
        }
    }
    
    //unwatermark according to Fire's formula
    var alpha_img, alpha_wm, factor1, alphaAdjust = document.getElementById('alphaAdjust').value
    for (let i = 0; i < w*h*4; i+=4) {
        //in case of automatic whole pixel alignment, the image's data requires an offset
        let j = i + img_pixel_offset + Math.floor(i/w/4)*(img_w-w)*4 //fixes mismatch of img_w vs w
        //alpha adjustment
        const alpha_watermark_adjusted = Math.min(alphaAdjust * imagedata_watermark.data[i+3], 255)
        //format: RGBA
        //(new pixel)=(1/(1-alpha))*(old pixel)+(-alpha/(1-alpha))*(watermark's pixel)
        if (alpha_watermark_adjusted > transparencyThreshold) {
            alpha_img = 255 / (255 - alpha_watermark_adjusted)
            alpha_wm = -alpha_watermark_adjusted / (255 - alpha_watermark_adjusted)
            imagedata_image.data[j] = Math.round(alpha_img * imagedata_image.data[j] + alpha_wm * imagedata_watermark.data[i])
            imagedata_image.data[j+1] = Math.round(alpha_img * imagedata_image.data[j+1] + alpha_wm * imagedata_watermark.data[i+1])
            imagedata_image.data[j+2] = Math.round(alpha_img * imagedata_image.data[j+2] + alpha_wm * imagedata_watermark.data[i+2])
            if (alpha_watermark_adjusted > opaqueThreshold) {
                //smoothing of very opaque pixels
                factor1 = (alpha_watermark_adjusted - opaqueThreshold) / (255 - opaqueThreshold)
                imagedata_image.data[j] = Math.round(factor1 * imagedata_image.data[j-4] + (1 - factor1) * imagedata_image.data[j])
                imagedata_image.data[j+1] = Math.round( factor1 * imagedata_image.data[j-3] + (1 - factor1) * imagedata_image.data[j+1])
                imagedata_image.data[j+2] = Math.round( factor1 * imagedata_image.data[j-2] + (1 - factor1) * imagedata_image.data[j+2])
            }
        }
    }
    
    if (postprocSmoothAlphaEdges) {
        var mask = new Uint8Array(imagedata_watermark.data.length/4)
        for (let i = w*4+7, j = w+1; i < w*h*4; i+=4, j++) {
            if (Math.abs(imagedata_watermark.data[i-4] - imagedata_watermark.data[i]) > edgeThreshold) {
                /*imagedata_image.data[i-3] = 0
                imagedata_image.data[i-2] = 255
                imagedata_image.data[i-1] = 0*/
                mask[j] = 255
                mask[j-1] = 255
                mask[j+1] = 255
            }
            if (Math.abs(imagedata_watermark.data[i-w*4] - imagedata_watermark.data[i]) > edgeThreshold) {
                /*imagedata_image.data[i-3] = 255
                imagedata_image.data[i-2] = 0
                imagedata_image.data[i-1] = 255*/
                mask[j] = 255
                mask[j-w] = 255
                mask[j+w] = 255
            }
        }
        var maskCopy = new Uint8Array(mask)
        var imagedata_imageCopy = new Uint8Array(imagedata_image.data)
        var stillPixelsLeftToSmooth = true
        while (stillPixelsLeftToSmooth) {
            stillPixelsLeftToSmooth = false
            var secondMask = new Uint8Array(maskCopy)
            for (let i = 0, j = 0; i < w*h*4; i+=4, j++) {
                if (secondMask[j] == 255) {
                    stillPixelsLeftToSmooth = true
                    //2x2 average but not those that should be smoothed
                    const pixelTop = j-w > 0 && secondMask[j-w] != 255
                    var pixelLeft = j-1 > 0 && secondMask[j-1] != 255
                    var pixelRight = j+1 > 0 && secondMask[j+1] != 255
                    var pixelBottom = j+w > 0 && secondMask[j+w] != 255
                    //make use of JS's wacky type conversion
                    var pixelCount = pixelTop + pixelBottom + pixelLeft + pixelRight
                    if (pixelCount > 1) {
                        for (var ii = 0; ii < 3; ii++) {
                            imagedata_imageCopy[i+ii] = (
                                (pixelTop ? imagedata_imageCopy[i-w*4+ii] : 0) +
                                (pixelLeft ? imagedata_imageCopy[i-4+ii] : 0) +
                                (pixelRight ? imagedata_imageCopy[i+4+ii] : 0) +
                                (pixelBottom ? imagedata_imageCopy[i+w*4+ii] : 0)
                                ) / pixelCount
                        }
                        maskCopy[j] = 0
                    }
                }
            }
        }
        //now for the really fucked up post processing
        var brightness = (r, g, b) => {
            //square average or something for now, better functions might be possible too
            //return Math.sqrt(r*r + g*g + b*b) / 3
            //return Math.hypot(r, g, b)
            
            //test luminance (perception) based brightness calculation
            return (r<<1+r+g<<2+b)>>3 * Math.random()
        }
        if (postprocAdjustBrightness) {
            for (let i = 0, j = 0; i < w*h*4; i+=4, j++) {
                if (mask[j] == 255) {
                    var factor = brightness(imagedata_imageCopy[i], imagedata_imageCopy[i+1], imagedata_imageCopy[i+2]) /
                                brightness(imagedata_image.data[i], imagedata_image.data[i+1], imagedata_image.data[i+2])
                    imagedata_image.data[i] = Math.round(imagedata_image.data[i] * factor)
                    imagedata_image.data[i+1] = Math.round(imagedata_image.data[i+1] * factor)
                    imagedata_image.data[i+2] = Math.round(imagedata_image.data[i+2] * factor)
                }
            }
        } else {
            for (let i = 0, j = 0; i < w*h*4; i+=4, j++) {
                if (mask[j] == 255) {
                    imagedata_image.data[i] = imagedata_imageCopy[i]
                    imagedata_image.data[i+1] = imagedata_imageCopy[i+1]
                    imagedata_image.data[i+2] = imagedata_imageCopy[i+2]
                }
            }
        }
    }
    
    if (document.getElementById('fullBatchZIP').checked && //document.getElementById('fullBatch').checked && 
            files.length > 1 && !isBatchZip) {
        isBatchZip = true
        zipFile = new NutLib.Zip()
    }

    ctx.putImageData(imagedata_image, img_x, img_y)

    if (document.getElementById('filterJPEG').checked) {
        function surfaceBlur(c, t, s) {
                //source canvas, threshold, blur strength
            if (t && t < 1) return;

            let ch1 = document.createElement('canvas')
            let ch2 = document.createElement('canvas')
            ch1.width = w
            ch1.height = h
            ch2.width = w
            ch2.height = h
            let c1x = ch1.getContext('2d')
            let c2x = ch2.getContext('2d')

            // imageContainer.appendChild(c)
            // imageContainer.appendChild(ch1)
            // ch1.style.top = h+'px'
            // imageContainer.appendChild(ch2)
            // ch2.style.top = h*2+'px'

            //edge detection
            c1x.filter = 'blur(1px)'
            c1x.drawImage(c, 0, 0)
            c2x.filter = 'blur(3px)'
            c2x.drawImage(c, 0, 0)
            c1x.globalCompositeOperation = 'difference'
            c1x.filter = 'none'
            c1x.drawImage(ch2, 0, 0)
            c1x.filter = 'grayscale()'
            c1x.globalCompositeOperation = 'copy'
            c1x.drawImage(ch1, 0, 0)

            //make a mask image off that
            c2x.filter = ''
            c2x.fillRect(0,0,w,h)
            let id2 = c2x.getImageData(0,0,w,h)
            let edge = c1x.getImageData(0,0,w,h)
            for (let i = 3; i < w*h*4; i+=4) {
                id2.data[i] = edge.data[i-1] > t ? 0 : 255
            }
            c2x.putImageData(id2, 0,0)

            //mask out the edges on the source image before blurring
            c1x.globalCompositeOperation = 'copy'
            c1x.filter = 'none'
            c1x.drawImage(c, 0, 0)
            c1x.globalCompositeOperation = 'destination-in'
            c1x.drawImage(ch2, 0, 0)
            c1x.globalCompositeOperation = 'copy'
            c1x.filter = 'blur('+s+'px)'
            c1x.drawImage(ch1, 0, 0)

            //write to the original canvas
            c1x.globalCompositeOperation = 'destination-in'
            c2x.filter = 'none'
            c1x.drawImage(ch2, 0, 0)
            c.getContext('2d').drawImage(ch1, 0, 0)

            //free up memory
            ch1.width = 0
            ch2.width = 0
        }

        const jfstrength = document.getElementById('filterJPEGStrength').value
        let cf = document.createElement('canvas')
        let cf_mask = document.createElement('canvas')
        cf.width = w
        cf.height = h
        cf_mask.width = w
        cf_mask.height = h
        let cfx = cf.getContext('2d')
        let cfx_mask = cf_mask.getContext('2d')
        cfx.putImageData(imagedata_image, 0, 0)
        cfx.filter = 'blur('+jfstrength+'px)'
        cfx.drawImage(cf, 0, 0)

        let cm1 = document.createElement('canvas')
        let cm2 = document.createElement('canvas')
        cm1.width = w
        cm1.height = h
        cm2.width = w
        cm2.height = h
        let cm1x = cm1.getContext('2d')
        let cm2x = cm2.getContext('2d')

        cm1x.fillStyle = '#000'
        cm1x.fillRect(0,0,w,h)
        cm1x.drawImage(c_wm, 0, 0)
        cm1x.globalCompositeOperation = 'difference'
        cm1x.filter = 'blur('+(1+jfstrength)+'px)'
        cm1x.drawImage(cm1, 0,0)

        cm2x.drawImage(c_wm, 0,0)
        cm2x.fillStyle = '#fff'
        cm2x.globalCompositeOperation = 'source-in'
        cm2x.fillStyle = '#000'
        cm2x.globalCompositeOperation = 'destination-over'
        cm2x.fillRect(0,0,w,h)
        cm2x.globalCompositeOperation = 'difference'
        cm2x.filter = 'blur('+(1+jfstrength)+'px)'
        cm2x.drawImage(cm2, 0,0)
        cfx_mask.drawImage(cm1, 0, 0)
        cfx_mask.globalCompositeOperation = 'lighten'
        cfx_mask.drawImage(cm2, 0, 0)
        cfx_mask.globalCompositeOperation = 'source-over'
        cfx_mask.filter = 'brightness(10) contrast(2)'
        cfx_mask.drawImage(cf_mask, 0, 0)
        let idf = cfx_mask.getImageData(0,0,w,h)
        for (let i = 0; i < idf.data.length; i+=4) {
            idf.data[i+3] = (idf.data[i]+idf.data[i+1]+idf.data[i+2]) > 10 ? 255 : 0
        }
        cfx_mask.putImageData(idf, 0,0)

        cfx.globalCompositeOperation = 'destination-in'
        cfx.drawImage(cf_mask, 0, 0)
        ctx.globalCompositeOperation = 'color'
        ctx.drawImage(cf, img_x, img_y)
        const sbthreshold = document.getElementById('filterJPEGThreshold').value
        if (sbthreshold > 0) {
            cfx.globalCompositeOperation = 'copy'
            cfx.filter = 'none'
            cfx.drawImage(ctx.canvas, img_x, img_y, w, h, 0, 0, w, h)
            surfaceBlur(cf, sbthreshold, jfstrength)
            cfx.globalCompositeOperation = 'destination-in'
            cfx.drawImage(cf_mask, 0, 0)
            ctx.globalCompositeOperation = 'source-over'
            ctx.drawImage(cf, img_x, img_y)
        }
        cf.width = 0
        cf_mask.width = 0
        cm1.width = 0
        cm2.width = 0
    }
    
    if (c.toBlob) {
        c.toBlob((blob) => {
            //A cascade of confusing if branches. to hopefully clear things up a bit:
            // - if we zip files and do full batch, we don't need to show a preview
            //   and thus take a shortcut to nextImage directly
            // - if we zip and do semi batch mode, we do need to still take the normal
            //   route through the preview afterwards if those are enabled
            // - if we zip and do semi batch but previews are disabled, we can also
            //   take the shortcut
            // - if zip is disabled, we cannot take the shortcut as we need to download
            //   each file individually and it's not really worth it to duplicate that code
            // - this in turn means that the full batch mode may also pass the preview
            //   code path
            if (isBatchZip) {
                if (document.getElementById('fullBatch').checked || !document.getElementById('preview').checked) {
                    blob.arrayBuffer().then(buffer => {
                        zipFile.addFile({
                            name: files[currentImage].name.replace(/\.[^\.]*$/, '_uwm.png'),
                            data: buffer
                        })
                        nextImage()
                    })
                    if (currentImage == files.length - 1) {
                        //usually handled by triggerDownload but we don't call that in batch mode
                        //because we don't get to see the results for long, with the exception of
                        //the last image of the batch
                        url = URL.createObjectURL(blob)
                        image.src = url
                    }
//                     nextImage()
                } else {
                    url = URL.createObjectURL(blob)
                    triggerDownload(url)
                }
            } else {
                url = URL.createObjectURL(blob)
                triggerDownload(url)
            }
        }, 'image/png')
    } else {
        url = c.toDataURL('image/png')
        triggerDownload(url)
    }
    c.width = 0
    c_wm.width = 0
}
function triggerDownload(url) {
    imageBackupUrl = image.src
    image.src = url
    if (document.getElementById('preview').checked && !(document.getElementById('fullBatch').checked && files.length > 1)) {
        watermark.style.display = 'none'
        rtPrev1.style.display = 'none'
        rtPrev2.style.display = 'none'
        rtPrevBox.style.display = 'none'
        document.getElementById('confirm').style.display = 'initial'
        document.getElementById('unwatermarkbuttons').style.display = 'none'
    } else {
        confirmYes()
    }
}
function confirmYes() {
    if (!isBatchZip) {
        //if because semi batch with preview and zip enabled might pass through here
        var a = document.createElement('a')
        a.href = image.src
        a.download = files[currentImage].name.replace(/\.[^\.]*$/, '_uwm.png')
        a.click()
        nextImage()
    } else {
        fetch(image.src).then(response => response.arrayBuffer()).then(buffer => {
            zipFile.addFile({
                name: files[currentImage].name.replace(/\.[^\.]*$/, '_uwm.png'),
                data: buffer
            })
            nextImage()
        })
    }
    imageBackupUrl = ''
    watermark.style.display = 'initial'
    setOverlayMode(document.getElementById('overlayMode').value)
    document.getElementById('confirm').style.display = 'none'
    document.getElementById('unwatermarkbuttons').style.display = 'initial'
}
function confirmNo() {
    URL.revokeObjectURL(image.src)
    image.src = imageBackupUrl
    watermark.style.display = 'initial'
    setOverlayMode(document.getElementById('overlayMode').value)
    document.getElementById('confirm').style.display = 'none'
    document.getElementById('unwatermarkbuttons').style.display = 'initial'
}
function confirmAnother() {
    imageBackupUrl = ''
    watermark.style.display = 'initial'
    setOverlayMode(document.getElementById('overlayMode').value)
    document.getElementById('confirm').style.display = 'none'
    document.getElementById('unwatermarkbuttons').style.display = 'initial'
    loadImage(image.src)
}
function nextImage() {
    currentImage++;
    if (files.length > 1) {
        console.log('Done with ' + currentImage + ' images out of ' + files.length)
    }
    //batch mode
    if (files.length > currentImage) {
        //get the position relative to default position
        //watermark's position will be adjusted on loading the next image
        switch (document.getElementById('defaultPosition').value) {
            case 'top-left':
                break;
            case 'top-right':
                wm_x = image.naturalWidth - wm_x
                break;
            case 'center':
                wm_x = wm_x - image.naturalWidth / 2
                wm_y = wm_y - image.naturalHeight / 2
                break;
            case 'bottom-left':
                wm_y = image.naturalHeight - wm_y
                break;
            case 'bottom-right':
                wm_x = image.naturalWidth - wm_x
                wm_y = image.naturalHeight - wm_y
                break;
        }
        wmPosIsUpdate = true
        loadImage()
    } else { 
        //end of batch mode, or single file
        if (isBatchZip) {
            //get the final zip file if enabled
            zipFile.finalize().then(zipBlob => {
                let zipURL = window.URL.createObjectURL(zipBlob)
                let a = document.createElement('a')
                a.href = zipURL
                a.download = "unwatermarked_" + (new Date()).valueOf() + ".zip"
                a.click()
                URL.revokeObjectURL(zipURL)
            })
                isBatchZip = false
                document.getElementById('skipbutton').style.display = 'none'
        }
    }
}

//front end stuff

function toggleOptions() {
    var settings = document.getElementById('settings')
    if (settings.style.display == 'none') {
        settings.style.display = 'initial'
    } else {
        settings.style.display = 'none'
    }
}
function zoomImage(delta) {
    //too avoid rounding errors
    if (zoomlevel + delta > 0 && zoomlevel + delta < 69501) {
        if (zoomlevel >= 4000) {
            delta *= 10
        } else if (zoomlevel >= 400) {
            delta *= 4
        }
        if (zoomlevel + delta == 100) {
            resetZoom()
        } else {
            imageContainer.style.width = String(parseFloat(imageContainer.style.width.slice(0, -2)) * (1 + delta / zoomlevel)) + 'px'
            imageContainer.style.height = String(parseFloat(imageContainer.style.height.slice(0, -2)) * (1 + delta / zoomlevel)) + 'px'
            image.style.width = String(parseFloat(image.style.width.slice(0, -2)) * (1 + delta / zoomlevel)) + 'px'
            image.style.height = String(parseFloat(image.style.height.slice(0, -2)) * (1 + delta / zoomlevel)) + 'px'
            scale_x = scale_x * (1 + delta / zoomlevel)
            scale_y = scale_y * (1 + delta / zoomlevel)
            updateWatermarkStyle()
            zoomlevel += delta
            zoomIndicator.innerHTML = zoomlevel
        }
        //memes
        switch (zoomlevel) {
            case 9000:
                alert("It's over 9000!")
                break
            case 20000:
                alert("Don't you have an image to unwatermark?")
                break
            case 40000:
                alert("\"It's time to STOP!\" — Filthy Frank, 2015")
                break
            case 60000:
                alert("Seriously, stop.")
                break
        }
        if (zoomlevel >= 69420) {
            image.src = 'https://lh3.googleusercontent.com/-mIPPBE0mJiI/YAfvZez4guI/AAAAAAAACm8/aGmUf2sTS7sZYI6YmTUJil6znNwTmpVIACOkEEAEYAw/s0/rickroll.gif'
            image.style.width = '100%'
            image.style.height = 'unset'
            imageContainer.style.width = '100%'
            imageContainer.style.height = 'unset'
        }
    }
}
function resetZoom() {
    zoomlevel = 100
    zoomIndicator.innerHTML = zoomlevel
    //copied from loadImage
    image.width = image.naturalWidth
    image.style.maxWidth = '100%'
    imageContainer.style.maxWidth = '100%'
    //reset previous styles
    imageContainer.style.height = 'unset'
    imageContainer.style.width = 'unset'
    image.style.height = 'unset'
    image.style.width = 'unset'
    var computedStyle = window.getComputedStyle(image)
    imageContainer.style.width = computedStyle.width
    imageContainer.style.height = computedStyle.height
    scale_x = parseFloat(computedStyle.width.replace('px','')) / parseInt(image.naturalWidth)
    scale_y = parseFloat(computedStyle.height.replace('px','')) / parseInt(image.naturalHeight)
    imageContainer.style.maxWidth = 'unset'
    image.style.maxWidth = 'unset'
    image.style.width = imageContainer.style.width
    image.style.height = imageContainer.style.height
    
    //watermark
    updateWatermarkStyle()
}
imageContainer.addEventListener('wheel', e => {
    if (e.ctrlKey) {
        e.preventDefault()
        if (e.deltaY > 0) {
            zoomImage(-10)
        } else {
            zoomImage(10)
        }
    }
})
document.getElementById('pixelatedZoom').addEventListener('change', e => {
    if (e.srcElement.checked) {
        imageContainer.className += " pixelated"
    } else {
        imageContainer.className = imageContainer.className.replace(' pixelated', '')
    }
})
function previewFilters(e) {
    if (e.srcElement.id == 'previewContrast') {
        filters.contrast = e.srcElement.value
    } else if (e.srcElement.id == 'previewBrightness') {
        filters.brightness = e.srcElement.value
    }
    image.style.filter = (filters.brightness != 1 ? 'brightness(' + filters.brightness + ') ' : '') +
                         (filters.contrast != 1 ? 'contrast(' + filters.contrast + ')' : '')
}
function resetFilter(id) {
    document.getElementById(id).value = 1;
    if (id == 'previewContrast') {
        filters.contrast = 1
    } else if (id == 'previewBrightness') {
        filters.brightness = 1
    }
    image.style.filter = (filters.brightness != 1 ? 'brightness(' + filters.brightness + ') ' : '') +
                         (filters.contrast != 1 ? 'contrast(' + filters.contrast + ')' : '')
}
document.getElementById('previewContrast').addEventListener('change', previewFilters)
document.getElementById('previewBrightness').addEventListener('change', previewFilters)
document.getElementById('showAdvanced').addEventListener('change', function(){
    document.getElementById('advancedOptions').style.display = this.checked ? 'initial' : 'none'
})
function setOverlayMode(mode) {
    if (mode == 'rt') {
        watermark.style.opacity = '0'
        rtPrev1.style.display = 'block'
        rtPrev2.style.display = 'block'
        rtPrevBox.style.display = 'block'
    } else {
        watermark.style.opacity = 'initial'
        watermark.style.mixBlendMode = mode
        rtPrev1.style.display = 'none'
        rtPrev2.style.display = 'none'
        rtPrevBox.style.display = 'none'
    }
}

//save/restore settings
window.addEventListener('unload', e => {
    var settings = {
        defaultPosition: document.getElementById('defaultPosition').value,
        overlayMode: document.getElementById('overlayMode').value,
        transparencyThreshold: document.getElementById('transparencyThreshold').value,
        preview: document.getElementById('preview').checked,
        autoSubpixel: document.getElementById('autoSubpixel').checked,
        smoothEdges: document.getElementById('smoothEdges').checked,
        adjustBrightness: document.getElementById('adjustBrightness').checked,
        fullBatch: document.getElementById('fullBatch').checked,
        fullBatchZIP: document.getElementById('fullBatchZIP').checked,
        pixelatedZoom: document.getElementById('pixelatedZoom').checked,
        showAdvanced: document.getElementById('showAdvanced').checked,
        autoWholePixel: document.getElementById('autoWholePixel').value,
        filterJPEG: document.getElementById('filterJPEG').checked,
        filterJPEGStrength: document.getElementById('filterJPEGStrength').value,
        filterJPEGThreshold: document.getElementById('filterJPEGThreshold').value
    }
    localStorage.setItem('settings', JSON.stringify(settings))
    delete e.returnValue
});
async function restoreSettingsFromLocalStorage() {
    if (localStorage.getItem('settings')) {
        var settings = JSON.parse(localStorage.getItem('settings'))
        document.getElementById('defaultPosition').value = settings.defaultPosition
        document.getElementById('overlayMode').value = settings.overlayMode
        setOverlayMode(settings.overlayMode)
        document.getElementById('transparencyThreshold').value = settings.transparencyThreshold
        document.getElementById('preview').checked = settings.preview
        document.getElementById('autoSubpixel').checked = settings.autoSubpixel
        document.getElementById('smoothEdges').checked = settings.smoothEdges
        document.getElementById('adjustBrightness').checked = settings.adjustBrightness
        document.getElementById('fullBatch').checked = settings.fullBatch
        document.getElementById('fullBatchZIP').checked = settings.fullBatchZIP
        document.getElementById('pixelatedZoom').checked = settings.pixelatedZoom
        if (settings.pixelatedZoom) {
            imageContainer.className += " pixelated"
        }
        document.getElementById('showAdvanced').checked = settings.showAdvanced
        if (settings.showAdvanced) {
            document.getElementById('advancedOptions').style.display = 'initial'
        }
        document.getElementById('autoWholePixel').value = settings.autoWholePixel
        document.getElementById('filterJPEG').checked = settings.filterJPEG
        document.getElementById('filterJPEGStrength').value = settings.filterJPEGStrength
        document.getElementById('filterJPEGThreshold').value = settings.filterJPEGThreshold
        document.getElementById('alphaAdjust').value = 1
    }
    // localFileStorageHandle = await navigator.storage.getDirectory()
    try {
        // let presetText = await localFileStorageHandle.getFileHandle('presets.json').then(f=>f.getFile()).then(f=>f.text())
        let presetText = localStorage.getItem('wmpreset')
        PRESETS = JSON.parse(presetText)
        for (let x of Object.values(PRESETS.watermarks)) {
            /*let wmFile = Uint8Array.fromBase64(x.file)
            x.url = URL.createObjectURL(new Blob([wmFile]))*/
            if (x.file && x.url && x.url.length < x.file.length) {
                x.url = 'data:image/png;base64,' + x.file
            }
        }
        for (let x of document.getElementById('preset').children) {
            if (PRESETS[x.value])
                x.innerText += PRESETS[x.value].name;
        }
    } catch (e) {
        console.log(e)
        PRESETS = {
            watermarks: {}
        }
    }
}
restoreSettingsFromLocalStorage()

//"extras" definition
var extras_on_image_load = {
    generateTencent: generateTencent_on_load,
    automaticPlacement: automaticPlacement_on_load,
    fuckNewtoki: fuckNewtoki_on_load
}
var extras_on_demand = {
    generateTencent: generateTencent_on_demand,
    resizeWatermark: resizeWatermark_on_demand,
    resizeWatermarkByPageSize: resizeWatermarkByPageSize_on_demand,
    automaticPlacement: automaticPlacement_on_demand,
    guessAlpha: guessAlpha_on_demand,
    fuckNewtoki: fuckNewtoki_on_demand,
    fullReset: resetAllSettings_on_demand
}
var resizer = new pica()
var tencent_source //= document.getElementById('tencent_watermark_source')
function generateTencent_on_load(resolve){
    //passthrough the resolve function because fuck that garbage
    //load watermark source
    if (!tencent_source) {
        tencent_source = new Image()
        tencent_source.onload = () => {
            tencent_source.onload = undefined
            generateTencent_resize(resolve)
        }
        tencent_source.crossOrigin = 'use-credentials'
        tencent_source.src = tencent_source_data_uri
    } else {
        //plainly resize it
        generateTencent_resize(resolve)
    }
}
function generateTencent_resize(resolve){
    //canvas for the resized watermark
    var c = document.createElement('canvas')
    //watermark size
    wm_w = 0.16 * image.naturalWidth
    c.width = Math.floor(wm_w)
    c.height = Math.round(0.0426455166286645 * image.naturalWidth);
    //resize it
    resizer.resize(tencent_source, c, {
        alpha: true,
        filter: 'lanczos3'
    }).then(c => {
        //add padding (need to transfer it to the watermark DOM element somehow anyway)
        watermark.width = c.width + 10
        watermark.height = c.height + 10
        watermark.getContext('2d').drawImage(c, 5, 5)
        //set correct position (20px from bottom right corner)
        //tencent presumably uses the (float) exact dimensions and then just casts to int,
        //leading to off-by-one positioning errors
        wm_x = Math.floor(image.naturalWidth - wm_w - 25)
        wm_y = image.naturalHeight - watermark.height - 15
        updateWatermarkStyle()
        //if this is called on load, we need to resolve the promise
        try {
            resolve('fuck this shit')
        } catch(e) {}
        console.debug('extra finished')
    }, /*error*/ console.log)
}
function generateTencent_on_demand() {
    generateTencent_on_load()
}

async function automaticPlacement_on_load(resolve) {
    if (!wm_ib) return;
    cv = await cv
    let inImg = cv.imread(image);
    let templ = cv.imread(watermark);

    let rgbaPlanes = new cv.MatVector();
    // Split the Mat
    cv.split(templ, rgbaPlanes);
    // Get A channel
    let alpha = rgbaPlanes.get(3);
    let thresh = new cv.Mat();
    cv.threshold(alpha, thresh, 5, 255, cv.THRESH_BINARY);
    rgbaPlanes.delete();
    alpha.delete();
    let mask = new cv.Mat();
    cv.cvtColor(thresh, mask, cv.COLOR_GRAY2RGBA, 0);
    // thresh is kept for edge matching mask

    // --- Method A: Standard Match ---
    let dst = new cv.Mat();
    cv.matchTemplate(inImg, templ, dst, cv.TM_CCOEFF_NORMED, mask);
    let resultStandard = cv.minMaxLoc(dst);
    let maxValStandard = resultStandard.maxVal;
    let maxLocStandard = resultStandard.maxLoc;

    // --- Method B: Inverted Image Match ---
    // Useful for white text on dark background when template is white
    let inImgRGB = new cv.Mat();
    cv.cvtColor(inImg, inImgRGB, cv.COLOR_RGBA2RGB);
    cv.bitwise_not(inImgRGB, inImgRGB);
    let inImgInv = new cv.Mat();
    cv.cvtColor(inImgRGB, inImgInv, cv.COLOR_RGB2RGBA);
    inImgRGB.delete();

    let dstInv = new cv.Mat();
    cv.matchTemplate(inImgInv, templ, dstInv, cv.TM_CCOEFF_NORMED, mask);
    let resultInv = cv.minMaxLoc(dstInv);
    let maxValInv = resultInv.maxVal;
    let maxLocInv = resultInv.maxLoc;
    inImgInv.delete(); dstInv.delete();

    // --- Method C: Edge Match (Canny) ---
    // Useful for noisy backgrounds
    let inImgGray = new cv.Mat();
    cv.cvtColor(inImg, inImgGray, cv.COLOR_RGBA2GRAY);
    let templGray = new cv.Mat();
    cv.cvtColor(templ, templGray, cv.COLOR_RGBA2GRAY);

    let inImgEdges = new cv.Mat();
    cv.Canny(inImgGray, inImgEdges, 50, 200);
    let templEdges = new cv.Mat();
    cv.Canny(templGray, templEdges, 50, 200);

    let dstEdge = new cv.Mat();
    // Use TM_CCORR_NORMED for edges with mask
    cv.matchTemplate(inImgEdges, templEdges, dstEdge, cv.TM_CCORR_NORMED, thresh);

    let resultEdge = cv.minMaxLoc(dstEdge);
    let maxValEdge = resultEdge.maxVal;
    let maxLocEdge = resultEdge.maxLoc;

    inImgGray.delete(); templGray.delete(); inImgEdges.delete(); templEdges.delete(); dstEdge.delete();

    // --- Decision Logic ---
    console.log(`Match Scores - Standard: ${maxValStandard.toFixed(4)}, Inv: ${maxValInv.toFixed(4)}, Edge: ${maxValEdge.toFixed(4)}`);

    let finalLoc = maxLocStandard;

    // Prioritize Standard if it's very good (stability)
    if (maxValStandard >= 0.8) {
        finalLoc = maxLocStandard;
    } else {
        // Fallback checks
        let bestScore = maxValStandard;

        // If Inverted is significantly better
        if (maxValInv > bestScore && maxValInv > 0.5) {
            bestScore = maxValInv;
            finalLoc = maxLocInv;
        }

        // If Edge is better (and reliable)
        // Edge matching scores are often lower, so threshold is lower
        if (maxValEdge > 0.5 && maxValEdge > bestScore) {
            finalLoc = maxLocEdge;
        }
    }

    inImg.delete(); templ.delete(); dst.delete(); mask.delete(); thresh.delete();
    wm_x = finalLoc.x
    wm_y = finalLoc.y
    updateWatermarkPosition()
    if (resolve) resolve('why');
    return
}
function automaticPlacement_on_demand() {
    automaticPlacement_on_load()
}

async function guessAlpha_on_demand() {
    if (!wm_ib) return;
    //get imagedata objects of the watermark area
        var x = parseInt(wm_x)
        var x_sub = wm_x % 1
        var y = parseInt(wm_y)
        var y_sub = wm_y % 1

        var w = Math.min(x + watermark.width, image.naturalWidth) - Math.max(x, 0)
        var h = Math.min(y + watermark.height, image.naturalHeight) - Math.max(y, 0)

        //prepare image canvas
        var c = document.createElement('canvas')
        var ctx = c.getContext('2d')
        c.width = w
        c.height = h
        ctx.drawImage(image, -Math.max(0, x), -Math.max(0, y))

        //prepare watermark canvas
        var c_wm = document.createElement('canvas')
        var ctx_wm = c_wm.getContext('2d')
        c_wm.width = watermark.width
        c_wm.height = watermark.height
        if (x_sub || y_sub) {
            ctx_wm.translate(x_sub, y_sub)
        }
        ctx_wm.drawImage(watermark, 0, 0)

        var imagedata_image = ctx.getImageData(0, 0, w, h)
        var imagedata_watermark = ctx_wm.getImageData(-Math.min(x, 0), -Math.min(y, 0), w, h)

        ctx_wm.fillStyle = '#808080'
        ctx_wm.fillRect(0, 0, c_wm.width, c_wm.height)
        ctx_wm.drawImage(watermark, 0, 0)
        var imagedata_watermark_gbg = ctx_wm.getImageData(-Math.min(x, 0), -Math.min(y, 0), w, h)

        //also grab an inverted copy for later
        ctx_wm.fillRect(0, 0, c_wm.width, c_wm.height)
        ctx_wm.filter = 'invert(1)'
        ctx_wm.drawImage(watermark, 0, 0)
        var imagedata_watermark_gbg_inv = ctx_wm.getImageData(-Math.min(x, 0), -Math.min(y, 0), w, h)

    //calc ssim scores for the possible alpha values
    let result = await (async function() {
        //written in a somewhat weird way to make it easier to port to multithreaded code

        const idwm = imagedata_watermark        //used to unwatermark
        const idim = imagedata_image
        const idres = new ImageData(idim.width, idim.height)    //stores the unwatermarked result
        const idtestwm = imagedata_watermark_gbg    // the watermark on a gray bg, used for the ssim testing
        const idinvtestwm = imagedata_watermark_gbg_inv    // the inverted watermark on a gray bg, prepared for the ssim testing

        const c = new OffscreenCanvas(idim.width, idim.height);
        const ctx = c.getContext('2d', {willReadFrequently: true})
        function edgy(id) {
            ctx.clearRect(0,0,c.width, c.height);
            ctx.putImageData(id, 0, 0)
            // if (true) {c.convertToBlob().then(b => {
            //     window.open(URL.createObjectURL(b))
            // })}
            ctx.filter = 'blur(1.5px)'
            ctx.globalCompositeOperation = 'difference'
            ctx.drawImage(c, 0, 0)
            // if (true) {c.convertToBlob().then(b => {
            //     window.open(URL.createObjectURL(b))
            // })}
            return ctx.getImageData(0, 0, c.width, c.height)
        }
        edgy(idim)
        const idtestedgy = edgy(idtestwm)

        idres.channels = 4
        idtestwm.channels = 4
        idinvtestwm.channels = 4
        idtestedgy.channels = 4

        let scores = []

        function testOpacity(alphaAdjust) {
            let min = Infinity, max = -Infinity
            //unwatermark according to Fire's formula
            let alpha_img, alpha_wm, factor1
            let heavyClippingDetected = false
            for (let i = 0; i < w*h*4; i+=4) {
                let j = i
                //alpha adjustment
                const alpha_watermark_adjusted = alphaAdjust * idwm.data[i+3]
                if (alpha_watermark_adjusted > 255) {
                    heavyClippingDetected = true
                    break
                }
                //format: RGBA
                //(new pixel)=(1/(1-alpha))*(old pixel)+(-alpha/(1-alpha))*(watermark's pixel)
                if (idwm.data[i+3] > 10) {
                    //threshold at 10
                    alpha_img = 255 / (255 - alpha_watermark_adjusted)
                    alpha_wm = -alpha_watermark_adjusted / (255 - alpha_watermark_adjusted) //1 - alpha_img
                    let r = (alpha_img * idim.data[j] + alpha_wm * idwm.data[i])
                    let g = (alpha_img * idim.data[j+1] + alpha_wm * idwm.data[i+1])
                    let b = (alpha_img * idim.data[j+2] + alpha_wm * idwm.data[i+2])
                    idres.data[j] = r
                    idres.data[j+1] = g
                    idres.data[j+2] = b
                    min = Math.min(min, r, g, b)
                    max = Math.max(max, r, g, b)
                    // if (Math.min(r,g,b) < -0x20 || Math.max(r,g,b) > 0x120) {
                    //     heavyClippingDetected = true
                    //     break
                    // }
                } else {
                    idres.data[i] = 0x80; idres.data[i+1] = 0x80; idres.data[i+2] = 0x80;
                }
                idres.data[i+3] = 0xFF;
            }

            // if (Math.round(alphaAdjust*100) == 75)  {
            //     let cx1 = rtPrev1.getContext('2d')
            //     cx1.putImageData(idres,0,0)
            // }
            if (heavyClippingDetected) {
                return {
                    normal: null,
                    inv: null,
                    alpha: null}

            } else {
                let scoreNormal = ImageSSIM.compare(idres, idtestwm)
                let scoreInverted = ImageSSIM.compare(idres, idinvtestwm)
                let resEdgy = edgy(idres)
                resEdgy.channels = 4
                let scoreEdgy = ImageSSIM.compare(resEdgy, idtestedgy)
                return {
                    // normal: scoreNormal,
                    // inv: scoreInverted,
                    edgy: scoreEdgy.mcs,
                    alpha: alphaAdjust,
                    min, max
                }
            }
        }

        let minmax = {
            normal: {min: Infinity, max: -Infinity},
            inv: {min: Infinity, max: -Infinity}
        }
        for (let i = 0.5; i < 1.2; i += 0.01) {
            let r = testOpacity(i)
            if (!r.alpha) continue;
            scores.push(r)
            for (let x in minmax) {
                if (r[x] > minmax[x].max) minmax[x].max = r[x];
                if (r[x] < minmax[x].min) minmax[x].min = r[x];
            }
        }
        //scale normal and inv 0 to 1
        minmax.normal.avg = (minmax.normal.max + minmax.normal.min)*0.5
        minmax.inv.avg = (minmax.inv.max + minmax.inv.min)*0.5
        let scoresScaled = scores.map(x => {
            return {
                alpha: x.alpha,
                comp: x.edgy //Math.max(x.normal - minmax.normal.avg, x.inv - minmax.inv.avg) //Math.max(n, i)
            }
        })
        scoresScaled.sort((a,b) => a.comp - b.comp)
        console.log(scores)
        window.lastScores = scoresScaled
        return scoresScaled[0]
    })()

    document.getElementById('alphaAdjust').value = result.alpha
    return
}

async function fuckNewtoki_on_load(resolve) {
    await automaticPlacement_on_load()
    await guessAlpha_on_demand()
    if (resolve) resolve('why');
    return
}
function fuckNewtoki_on_demand() {
    fuckNewtoki_on_load()
}

function resizeWatermark_on_demand() {
    let w = parseInt(prompt("Enter the resized watermark's new width.\nLeave empty to calculate it relative to its height."))
    let h = parseInt(prompt("Enter the resized watermark's new height.\nLeave empty to calculate it relative to its width."))
    if (!w && !h) {
        alert('At least one dimension needs to be entered.')
    } else {
        if (!w) {
            w = watermark.width / watermark.height * h
        }
        if (!h) {
            h = watermark.height / watermark.width * w
        }
        resizeWatermark(w, h)
    }
}
function resizeWatermarkByPageSize_on_demand() {
    let source_w = parseInt(prompt("Enter the width of the watermark sample's source page.\nLeave empty to calculate it relative to its height."))
    let source_h = parseInt(prompt("Enter the height of the watermark sample's source page.\nLeave empty to calculate it relative to its width."))
    if (!source_w && !source_h) {
        alert('At least one dimension needs to be entered.')
    } else {
        let ratio
        if (!source_w) {
            ratio = image.naturalHeight / source_h
        }
        if (!source_h) {
            ratio = image.naturalWidth / source_w
        }
        let r_w = watermark.width * ratio, r_h = watermark.height * ratio
        resizeWatermark(watermark.width * ratio, watermark.height * ratio)
        alert("Resized to:\nWidth: "+r_w+"\nHeight: "+r_h)
    }
}
var hasBeenResized = false
var watermark_backup = document.createElement('canvas')
function resizeWatermark(w, h) {
    watermark.width = Math.round(w)
    watermark.height = Math.round(h)
    resizer.resize(wm_ib, watermark, {
        alpha: true,
        filter: 'lanczos3'
    }).then(
        () => updateWatermarkStyle()
    )
}

function run_extra() {
    if (extras_on_demand.hasOwnProperty(extras_dropdown.value)) {
        extras_on_demand[extras_dropdown.value]()
    }
}

function resetAllSettings_on_demand() {
    var settings = {
        defaultPosition: 'top-left',
        overlayMode: "normal",
        transparencyThreshold: 3,
        preview: true,
        autoSubpixel: false,
        smoothEdges: false,
        adjustBrightness: false,
        fullBatch: false,
        fullBatchZIP: true,
        pixelatedZoom: false,
        showAdvanced: true,
        autoWholePixel: 0,
        filterJPEG: false,
        filterJPEGStrength: 3,
        filterJPEGThreshold: 4
    }
    localStorage.setItem('settings', JSON.stringify(settings))
    restoreSettingsFromLocalStorage()
    resetFilter('previewBrightness')
    resetFilter('previewContrast')
    localStorage.setItem('wmpreset', '{"watermarks": {}}')
    location.reload()
}


async function savePreset() {
    let preset = {
        name: document.getElementById('presetName').value.trim(),
        anchor: document.getElementById('defaultPosition').value,
        img: null,
        offset: {
            x: document.getElementById('relPosX').innerText.trim(),
            y: document.getElementById('relPosY').innerText.trim()
        },
        jpegFilter: document.getElementById('presetJPEG').checked ? ({
            enabled: document.getElementById('filterJPEG').checked,
            radius: document.getElementById('filterJPEGStrength').value,
            threshold: document.getElementById('filterJPEGThreshold').value
        }) : null,
        alphaAdjust: document.getElementById('alphaAdjust').value != 1 ?
            document.getElementById('alphaAdjust').value : null
    }
    if (document.getElementById('presetWatermark').checked) {
        let wmFile = await fetch(wm_img_load.src).then(r=>r.bytes())
        const hashbytes = new Uint8Array(await window.crypto.subtle.digest("SHA-256", wmFile));
        let hash
        if (hashbytes.toBase64) {
            hash = hashbytes.toBase64()
        } else {
            hash = await new Promise((resolve) => {
                let fr = new FileReader()
                fr.onload = () => {
                    resolve(fr.result.substring(fr.result.indexOf(',')+1))
                }
                fr.readAsDataURL(new Blob([hashbytes]))
            })
        }
        preset.img = {
            hash: hash,
            w: wm_ib.width,
            h: wm_ib.height
        }
        if (!PRESETS.watermarks[hash]) {
            let wmDataURL = await new Promise((resolve) => {
                let fr = new FileReader()
                fr.onload = () => {
                    resolve(fr.result)
                }
                fr.readAsDataURL(new Blob([wmFile]))
            })
            PRESETS.watermarks[hash] = {
                url: wmDataURL
            }
        }
    }
    PRESETS[document.getElementById("preset").value] = preset
    let unusedFiles = Object.keys(PRESETS.watermarks)
    for (let x of document.getElementById('preset').children) {
        let hash = PRESETS[x.value]?.img?.hash
        if (hash) {
            let i = unusedFiles.indexOf(hash)
            if (i >= 0) unusedFiles.splice(i, 1);
        }
    }
    for (let x of unusedFiles) {
        delete PRESETS.watermarks[x]
    }
    /*let fHandle = await localFileStorageHandle.getFileHandle('presets.json', { create: true })
    await NutLib.Parallel.from(
        async (arg) => {
            let writer = await arg.handle.createSyncAccessHandle()
            let text = new TextEncoder().encode(arg.text)
            writer.truncate(text.byteLength)
            writer.write(text)
            writer.flush()
            writer.close()
        },
        [{handle: fHandle, text: JSON.stringify(PRESETS)}],
        1
    )*/
    localStorage.setItem('wmpreset', JSON.stringify(PRESETS))
    for (let x of document.getElementById('preset').children) {
        if (PRESETS[x.value])
            x.innerText = x.value + ': ' + PRESETS[x.value].name;
    }
}
async function exportPreset() {
    // let presetfile = await localFileStorageHandle.getFileHandle('presets.json').then(f=>f.getFile())
    let presetfile = new Blob([localStorage.getItem('wmpreset')])
    let url = URL.createObjectURL(presetfile)
    let a = document.createElement('a')
    a.href = url
    a.download = "wm_presets.json"
    a.click()
    URL.revokeObjectURL(url)
}
async function importPreset() {
    document.getElementById('presetFileLoader').click()
}
document.getElementById('presetFileLoader').onchange = async function(){
    let text = await this.files[0].text()
    /*let fHandle = await localFileStorageHandle.getFileHandle('presets.json', { create: true })
    NutLib.Parallel.from(
        async (arg) => {
            let writer = await arg.handle.createSyncAccessHandle()
            let text = new TextEncoder().encode(arg.text)
            writer.truncate(text.byteLength)
            writer.write(text)
            writer.flush()
            writer.close()
        },
        [{handle: fHandle, text: text}], 1
    ).then(() => location.reload())*/
    localStorage.setItem('wmpreset', text)
    location.reload()
}
async function applyPreset() {
    if (!PRESETS) return;
    let preset = PRESETS[document.getElementById("preset").value]
    currentPreset = preset
    if (!preset) return;

    document.getElementById('presetName').value = preset.name

    document.getElementById('presetWatermark').checked = !!preset.img
    if (preset.img) {
        let url = PRESETS.watermarks[preset.img.hash].url
        await loadWatermark(url)
    }

    if (!wm_ib) return;

    document.getElementById('presetJPEG').checked = !!preset.jpegFilter
    if (preset.jpegFilter) {
        document.getElementById('filterJPEG').checked = preset.jpegFilter.enabled
        document.getElementById('infoJpegOn').checked = preset.jpegFilter.enabled
        if (preset.jpegFilter.enabled) {
            document.getElementById('filterJPEGStrength').value = preset.jpegFilter.radius
            document.getElementById('infoJpegRadius').innerText = preset.jpegFilter.radius
            document.getElementById('filterJPEGThreshold').value = preset.jpegFilter.threshold
            document.getElementById('infoJpegThreshold').innerText = preset.jpegFilter.threshold
        }
    }
    document.getElementById('infoAlphaWrapper').style.display = (!preset.alphaAdjust || preset.alphaAdjust == 1) ? 'none' : '';
    if (preset.alphaAdjust) {
        document.getElementById('alphaAdjust').value = preset.alphaAdjust
        document.getElementById('infoAlpha').innerText = preset.alphaAdjust
    } else {
        document.getElementById('alphaAdjust').value = 1
    }

    document.getElementById('relPosX').innerText = preset.offset.x
    document.getElementById('relPosY').innerText = preset.offset.y
    document.getElementById('defaultPosition').value = preset.anchor

    //position
    let x = 0, y = 0
    //first x
    switch (preset.anchor) {
        case 'top-left':
        case 'center-left':
        case 'bottom-left':
            x = 0
            break
        case 'top-center':
        case 'center':
        case 'bottom-center':
            x = Math.round((image.naturalWidth - wm_ib.width) * 0.5)
            break
        case 'top-right':
        case 'center-right':
        case 'bottom-right':
            x = image.naturalWidth - wm_ib.width
            break
    }
    //now y
    if (preset.anchor.startsWith('top')) {
        y = 0
    }
    if (preset.anchor.startsWith('center')) {
        y = Math.round((image.naturalHeight - wm_ib.height) * 0.5)
    }
    if (preset.anchor.startsWith('bottom')) {
        y = image.naturalHeight - wm_ib.height
    }
    wm_x = x + parseFloat(preset.offset.x)
    wm_y = y + parseFloat(preset.offset.y)
    updateWatermarkPosition()
}
document.getElementById('filterJPEG').addEventListener('change', function(){
    document.getElementById('infoJpegOn').checked = this.checked
})
document.getElementById('filterJPEGStrength').addEventListener('change', function(){
    document.getElementById('infoJpegRadius').innerText = this.value
})
document.getElementById('filterJPEGThreshold').addEventListener('change', function(){
    document.getElementById('infoJpegThreshold').innerText = this.value
})
document.getElementById('alphaAdjust').addEventListener('change', function(){
    document.getElementById('infoAlphaWrapper').style.display = this.value != 1 ? '' : 'none';
    document.getElementById('infoAlpha').innerText = this.value
})


document.addEventListener("keypress", e => {
    if (Array.prototype.some.call(document.getElementById('preset').children, (x) => x.value == e.key)) {
        document.getElementById("preset").value = e.key
        applyPreset()
        return
    }
    if (document.getElementById('unwatermarkbuttons').style.display != 'none') {
        if (e.key == 'Enter') {
            e.preventDefault()
            unwatermark()
        }
        if (e.key == '.' || e.key == ',' && document.getElementById('skipbutton').style.display != 'none') {
            nextImage()
        }
    } else {
        if (e.key == 'Enter') {
            e.preventDefault()
            confirmYes()
        }
        if (e.key == '-') {
            confirmNo()
        }
        if (e.key == '+') {
            confirmAnother()
        }
    }
    /*let d = ''
    switch (e.key) {
        case '1':
            d = 'top-left'
            break
        case '2':
            d = 'top-right'
            break
        case '3':
            d = 'center'
            break
        case '4':
            d = 'bottom-left'
            break
        case '5':
            d = 'bottom-right'
            break
        default:
            return
    }
    document.getElementById('defaultPosition').value = d
    if (d == 'center') {
        wm_x = Math.round((image.naturalWidth - watermark.width) / 2)
        wm_y = Math.round((image.naturalHeight - watermark.height) / 2)
    } else {
        if (d.indexOf('right') > -1) {
            wm_x = image.naturalWidth - watermark.width
        } else {
            wm_x = 0
        }
        if (d.indexOf('bottom') > -1) {
            wm_y = image.naturalHeight - watermark.height
        } else {
            wm_y = 0
        }
    }
    updateWatermarkPosition()*/
})
